package `in`.canvs.homecontrol.miraie

import android.content.Context
import `in`.canvs.homecontrol.data.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class AcUiState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val cloudConnected: Boolean = false,
    val localOnly: Boolean = false,
    val devices: List<AcState> = emptyList(),
)

/**
 * App-wide owner of MirAIe ACs. Local LAN control (UDP 5001) is preferred; MQTT is the fallback.
 * The cloud is only needed once to bootstrap device list and LAN keys, which are cached encrypted.
 */
class AcRepository(context: Context, private val store: SecureStore) {

    private val api = MiraieApi(store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, MiraieMqtt>()
    private val local = LocalAcTransport(context, ::onLocalStatus)
    private var reconnectJob: Job? = null

    private val _state = MutableStateFlow(
        AcUiState(
            loggedIn = api.isLoggedIn || store.devicesCache != null,
            localOnly = store.localOnly,
            devices = loadCache(),
        ),
    )
    val state: StateFlow<AcUiState> = _state.asStateFlow()

    private val localOnly get() = store.localOnly

    suspend fun login(login: String, password: String) {
        _state.update { it.copy(loading = true, error = null) }
        try {
            api.login(login, password)
            _state.update { it.copy(loggedIn = true) }
            refresh(forceCloud = true)
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Login failed") }
        }
    }

    fun logout() {
        closeConnections()
        local.stop()
        api.logout()
        _state.value = AcUiState(localOnly = store.localOnly)
    }

    fun setLocalOnly(enabled: Boolean) {
        store.localOnly = enabled
        _state.update { it.copy(localOnly = enabled) }
        if (enabled) {
            closeConnections()
            _state.update { s ->
                s.copy(
                    cloudConnected = false,
                    devices = s.devices.map { if (it.transport == Transport.Cloud) it.copy(transport = Transport.Offline) else it },
                )
            }
        }
        scope.launch { refresh() }
    }

    /**
     * Loads devices (from cache in local-only mode, else from the cloud), then picks a transport per AC:
     * local if LAN discovery answers within ~3 s, otherwise MQTT (unless local-only).
     * [forceCloud] re-fetches device list and LAN keys from the API even in local-only mode.
     */
    suspend fun refresh(forceCloud: Boolean = false): Unit = withContext(Dispatchers.IO) {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val cached = loadCache()
            val previous = _state.value.devices.associateBy { it.device.deviceId }
            var devices = if (localOnly && cached.isNotEmpty() && !forceCloud) {
                cached
            } else {
                if (!api.isLoggedIn) {
                    _state.update { it.copy(loading = false) }
                    return@withContext
                }
                fetchFromCloud(cached.associateBy { it.device.deviceId })
            }
            // Keep last known control values across refreshes.
            devices = devices.map { d ->
                previous[d.device.deviceId]?.let { p ->
                    d.copy(
                        power = p.power, mode = p.mode, temperature = p.temperature, fanSpeed = p.fanSpeed,
                        roomTemperature = p.roomTemperature, rawStatus = d.rawStatus + p.rawStatus,
                    )
                } ?: d
            }
            if (!localOnly) {
                devices = devices.map { d ->
                    runCatching { api.status(d.device.deviceId) }.getOrNull()?.let { applyStatus(d, it) } ?: d
                }
            }
            devices = selectTransports(devices)
            _state.update { it.copy(loading = false, devices = devices) }

            if (!localOnly && devices.any { it.transport == Transport.Cloud }) connectMqtt(devices)
        } catch (e: Exception) {
            if (e is MiraieException && e.code == 401 && !localOnly) logout()
            _state.update { it.copy(loading = false, error = e.message ?: "Failed to load devices") }
        }
    }

    // ---- key backup (sign-in-free local control) -------------------------------------------------

    /** ACs whose LAN keys are known. */
    val exportable: List<AcState> get() = _state.value.devices.filter { it.localKeys != null }

    /**
     * One copyable code holding every AC's LAN key. Same format as the Mac app
     * (`miraie-local-v1:` + base64 of a JSON array with sorted keys), so codes work on both.
     */
    fun exportKeyCode(): String? {
        val entries = exportable.map { d ->
            val l = d.localKeys!!
            sortedJson(
                "id" to d.device.deviceId,
                "name" to d.device.name,
                "space" to d.device.space,
                "homeId" to d.device.homeId,
                "topic" to d.device.topic,
                "modelId" to d.device.modelId,
                "keys" to RawJson(sortedJson(*KEY_CODE_NAMES.map { (name, cap) -> name to d.key(cap) }.toTypedArray())),
                "mac" to l.mac,
                "ip" to l.ip,
                "key" to l.keyHex,
                "iv" to l.ivHex,
                "model" to d.modelJson,
            )
        }
        if (entries.isEmpty()) return null
        val json = entries.joinToString(",", "[", "]")
        return KEY_CODE_PREFIX + android.util.Base64.encodeToString(json.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** Restores ACs from a key code and switches to local-only control; no Panasonic sign-in needed. */
    fun importKeyCode(text: String) {
        val trimmed = text.trim()
        val devices = runCatching {
            require(trimmed.startsWith(KEY_CODE_PREFIX))
            val json = String(android.util.Base64.decode(trimmed.removePrefix(KEY_CODE_PREFIX), android.util.Base64.DEFAULT))
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val k = o.optJSONObject("keys") ?: JSONObject()
                AcState(
                    device = MiraieDevice(
                        deviceId = o.getString("id"),
                        name = o.optString("name", "AC"),
                        modelId = o.optString("modelId").takeIf { it.isNotEmpty() },
                        category = "AC",
                        topic = o.getString("topic"),
                        homeId = o.getString("homeId"),
                        space = o.optString("space"),
                    ),
                    keys = Capability.entries.associateWith { c ->
                        KEY_CODE_NAMES.firstOrNull { it.second == c }?.let { k.optString(it.first) }?.ifEmpty { null } ?: c.defaultKey
                    },
                    udpSupported = true,
                    localKeys = LocalKeys(
                        mac = o.getString("mac"),
                        ip = o.optString("ip").takeIf { it.isNotEmpty() },
                        keyHex = o.getString("key"),
                        ivHex = o.getString("iv"),
                    ),
                ).withModel(o.optString("model").takeIf { it.isNotEmpty() })
            }.also { require(it.isNotEmpty()) }
        }.getOrNull()
        if (devices == null) {
            _state.update { it.copy(error = "That isn't a valid AC key code.") }
            return
        }
        saveCache(devices)
        store.localOnly = true
        closeConnections()
        _state.update { it.copy(loggedIn = true, localOnly = true, error = null, devices = devices) }
        scope.launch { refresh() }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Shareable JSON (see [Diagnostics]): no password, tokens or full LAN keys. */
    fun diagnosticsJson(appVersion: String): String = Diagnostics.build(appVersion, _state.value.devices)

    /** Debug builds only: sample ACs for UI screenshots (`adb shell am start ... --ez demo true`). */
    fun seedDemo() {
        fun dev(id: String, name: String, space: String) = MiraieDevice(id, name, null, "AC", "h/$id", "h", space)
        _state.value = AcUiState(
            loggedIn = true,
            devices = listOf(
                AcState(dev("d1", "Bedroom AC", "Bedroom"), transport = Transport.Local, power = true, mode = "cool",
                    temperature = 24.0, roomTemperature = 28.0, fanSpeed = "auto",
                    localKeys = LocalKeys("AA:BB:CC:DD:EE:FF", "192.168.1.50", "00112233445566778899aabbccddeeff", "0f0e0d0c0b0a09080706050403020100"),
                    rawStatus = mapOf("ps" to "on", "acmd" to "cool", "actmp" to "24", "rmtmp" to "28", "rmhmd" to 62,
                        "acfdl" to 2, "accpc" to 860, "acvs" to 0, "achs" to 3, "acdc" to "on", "acem" to "off",
                        "acpm" to "on", "acec" to "off", "acng" to "on", "accnv" to 80, "acflt" to ""),
                ).withModel(DEMO_MODEL),
                AcState(dev("d2", "Living Room AC", "Living Room"), transport = Transport.Cloud, power = false, mode = "dry",
                    temperature = 25.5, roomTemperature = 30.0, fanSpeed = "low"),
            ),
        )
    }

    fun setPower(deviceId: String, on: Boolean) =
        send(deviceId, Capability.Power, if (on) "on" else "off") { it.copy(power = on) }

    fun setMode(deviceId: String, mode: String) =
        send(deviceId, Capability.Mode, mode) { it.copy(mode = mode) }

    fun setFanSpeed(deviceId: String, speed: String) =
        send(deviceId, Capability.FanSpeed, speed) { it.copy(fanSpeed = speed) }

    fun setTemperature(deviceId: String, temp: Double) {
        val clamped = temp.coerceIn(16.0, 30.0)
        val text = if (clamped % 1.0 == 0.0) clamped.toInt().toString() else clamped.toString()
        send(deviceId, Capability.Temperature, text) { it.copy(temperature = clamped) }
    }

    // ---- cloud bootstrap -------------------------------------------------------------------------

    private suspend fun fetchFromCloud(cached: Map<String, AcState>): List<AcState> = withContext(Dispatchers.IO) {
        val all = api.homes().flatMap { it.devices }
        val acs = all.filter { it.category?.contains("ac", ignoreCase = true) == true }.ifEmpty { all }
        val modelCache = ConcurrentHashMap<String, MiraieApi.ModelResult>()
        val states = acs.map { device ->
            async {
                val model = device.modelId?.let { id ->
                    modelCache[id] ?: runCatching { api.modelInfo(id) }.getOrNull()?.also { modelCache[id] = it }
                }
                val keys = runCatching { api.localKeys(device.deviceId) }.getOrNull()
                    ?: cached[device.deviceId]?.localKeys
                val old = cached[device.deviceId]
                AcState(
                    device = device,
                    keys = model?.keys ?: old?.keys ?: Capability.entries.associateWith { it.defaultKey },
                    udpSupported = model?.udp ?: old?.udpSupported,
                    localKeys = keys,
                    rawStatus = old?.rawStatus.orEmpty(),
                ).withModel(model?.rawJson ?: old?.modelJson)
            }
        }.awaitAll()
        saveCache(states)
        states
    }

    // ---- transport selection --------------------------------------------------------------------

    private suspend fun selectTransports(devices: List<AcState>): List<AcState> = withContext(Dispatchers.IO) {
        val localCandidates = devices.filter { it.localKeys != null && it.udpSupported != false }
        val localStarted = localCandidates.isNotEmpty() && runCatching { local.start() }.isSuccess
        devices.map { d ->
            async {
                val ip = if (localStarted && d.localKeys != null && d.udpSupported != false) {
                    local.register(d.device.deviceId, d.localKeys)
                    local.discover(d.device.deviceId, timeoutMs = 3000)
                } else {
                    null
                }
                when {
                    ip != null -> {
                        persistIp(d.device.deviceId, ip)
                        d.copy(transport = Transport.Local, online = true)
                    }
                    localOnly -> d.copy(transport = Transport.Offline)
                    else -> d.copy(transport = Transport.Cloud)
                }
            }
        }.awaitAll()
    }

    /**
     * Sends any capability by its wire key (from the device model). Local UDP always uses `ki = 1`,
     * MQTT uses [ki]. The raw status is updated optimistically.
     */
    fun sendRaw(deviceId: String, key: String, value: Any, ki: Int) =
        sendKey(deviceId, key, ki, value) { applyStatus(it, JSONObject().put(key, value)) }

    private fun send(deviceId: String, cap: Capability, value: Any, optimistic: (AcState) -> AcState) =
        sendKey(deviceId, null, cap.ki, value, cap, optimistic)

    private fun sendKey(
        deviceId: String,
        rawKey: String?,
        ki: Int,
        value: Any,
        cap: Capability? = null,
        optimistic: (AcState) -> AcState,
    ) {
        val ac = _state.value.devices.firstOrNull { it.device.deviceId == deviceId } ?: return
        val key = rawKey ?: ac.key(cap!!)
        updateDevice(deviceId) { optimistic(it).copy(rawStatus = it.rawStatus + (key to value)) }
        scope.launch {
            try {
                when {
                    ac.transport == Transport.Local -> try {
                        local.send(deviceId, key, value)
                    } catch (e: Exception) {
                        if (localOnly) throw e
                        updateDevice(deviceId) { it.copy(transport = Transport.Cloud) }
                        sendCloud(ac, key, ki, value)
                    }
                    localOnly -> {
                        // Not found earlier; retry discovery once before giving up.
                        if (ac.localKeys == null) error("No LAN keys yet — use \"Refresh keys\" once with internet")
                        runCatching { local.start() }
                        local.register(deviceId, ac.localKeys)
                        local.discover(deviceId) ?: error("${ac.device.name} not found on the local network")
                        updateDevice(deviceId) { it.copy(transport = Transport.Local, online = true) }
                        local.send(deviceId, key, value)
                    }
                    else -> sendCloud(ac, key, ki, value)
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Send failed: ${e.message}") }
            }
        }
    }

    private suspend fun sendCloud(ac: AcState, key: String, ki: Int, value: Any) {
        val conn = connections[ac.device.homeId] ?: run {
            connectMqtt(_state.value.devices)
            connections[ac.device.homeId]
        } ?: error("No cloud connection")
        if (!conn.isConnected) conn.connect(api.validToken())
        conn.publishControl(ac.device.topic, key, value, ki)
    }

    // ---- MQTT -----------------------------------------------------------------------------------

    private suspend fun connectMqtt(devices: List<AcState>) {
        closeConnections()
        if (localOnly) return
        val userId = store.userId.orEmpty()
        val token = api.validToken()
        devices.groupBy { it.device.homeId }.forEach { (homeId, list) ->
            val conn = MiraieMqtt(
                homeId = homeId,
                userId = userId,
                statusTopics = list.map { it.device.topic },
                onStatus = ::onMqttStatus,
                onConnectionChange = ::onConnectionChange,
            )
            connections[homeId] = conn
            runCatching { conn.connect(token) }.onFailure { onConnectionChange(false) }
        }
    }

    private fun onMqttStatus(topic: String, payload: JSONObject) {
        _state.update { s ->
            s.copy(devices = s.devices.map { if (it.device.topic == topic) applyStatus(it, payload) else it })
        }
    }

    private fun onLocalStatus(deviceId: String, payload: JSONObject) {
        updateDevice(deviceId) { applyStatus(it, payload).copy(transport = Transport.Local, online = true) }
    }

    private fun onConnectionChange(connected: Boolean) {
        _state.update { it.copy(cloudConnected = connections.values.any { c -> c.isConnected }) }
        if (!connected && !localOnly) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var delayMs = 2_000L
            while (!localOnly && connections.values.any { !it.isConnected }) {
                delay(delayMs)
                val token = runCatching { api.validToken() }.getOrNull() ?: break
                connections.values.filter { !it.isConnected }.forEach { runCatching { it.connect(token) } }
                delayMs = (delayMs * 2).coerceAtMost(64_000L)
            }
            _state.update { it.copy(cloudConnected = connections.values.any { c -> c.isConnected }) }
        }
    }

    private fun closeConnections() {
        reconnectJob?.cancel()
        connections.values.forEach { it.close() }
        connections.clear()
    }

    // ---- state helpers --------------------------------------------------------------------------

    private fun updateDevice(deviceId: String, transform: (AcState) -> AcState) {
        _state.update { s ->
            s.copy(devices = s.devices.map { if (it.device.deviceId == deviceId) transform(it) else it })
        }
    }

    private fun applyStatus(ac: AcState, j: JSONObject): AcState {
        val merged = ac.rawStatus.toMutableMap()
        j.keys().forEach { k -> merged[k] = j.opt(k)?.takeIf { it != JSONObject.NULL } }
        var s = ac.copy(rawStatus = merged)
        j.optString(ac.key(Capability.Power)).takeIf { it.isNotEmpty() }?.let { s = s.copy(power = it == "on") }
        j.optString(ac.key(Capability.Mode)).takeIf { it.isNotEmpty() }?.let { s = s.copy(mode = it) }
        j.optString(ac.key(Capability.FanSpeed)).takeIf { it.isNotEmpty() }?.let { s = s.copy(fanSpeed = it) }
        j.optString(ac.key(Capability.Temperature)).toDoubleOrNull()?.let { s = s.copy(temperature = it) }
        j.optString(ac.key(Capability.RoomTemperature)).toDoubleOrNull()?.let { s = s.copy(roomTemperature = it) }
        if (j.has("onlineStatus")) {
            val v = j.optString("onlineStatus").lowercase()
            s = s.copy(online = v == "true" || v == "online" || v == "1")
        }
        return s
    }

    // ---- encrypted device cache -----------------------------------------------------------------

    private fun persistIp(deviceId: String, ip: String) {
        val list = loadCache()
        val updated = list.map { d ->
            if (d.device.deviceId == deviceId && d.localKeys != null && d.localKeys.ip != ip) {
                d.copy(localKeys = d.localKeys.copy(ip = ip))
            } else {
                d
            }
        }
        if (updated != list) saveCache(updated)
    }

    private fun saveCache(devices: List<AcState>) {
        val arr = JSONArray()
        devices.forEach { d ->
            val keys = JSONObject()
            d.keys.forEach { (c, k) -> keys.put(c.name, k) }
            arr.put(
                JSONObject()
                    .put("deviceId", d.device.deviceId)
                    .put("name", d.device.name)
                    .put("modelId", d.device.modelId)
                    .put("category", d.device.category)
                    .put("topic", d.device.topic)
                    .put("homeId", d.device.homeId)
                    .put("space", d.device.space)
                    .put("keys", keys)
                    .put("udpSupported", d.udpSupported)
                    .put("model", d.modelJson)
                    .put("status", JSONObject(d.rawStatus.mapValues { it.value ?: JSONObject.NULL }))
                    .put(
                        "local",
                        d.localKeys?.let {
                            JSONObject().put("mac", it.mac).put("ip", it.ip).put("key", it.keyHex).put("iv", it.ivHex)
                        },
                    ),
            )
        }
        store.devicesCache = arr.toString()
    }

    private fun loadCache(): List<AcState> {
        val text = store.devicesCache ?: return emptyList()
        return runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val keysJson = o.optJSONObject("keys") ?: JSONObject()
                val keys = Capability.entries.associateWith { c -> keysJson.optString(c.name).ifEmpty { c.defaultKey } }
                val l = o.optJSONObject("local")
                AcState(
                    device = MiraieDevice(
                        deviceId = o.getString("deviceId"),
                        name = o.optString("name", "AC"),
                        modelId = o.optString("modelId").takeIf { it.isNotEmpty() },
                        category = o.optString("category").takeIf { it.isNotEmpty() },
                        topic = o.getString("topic"),
                        homeId = o.getString("homeId"),
                        space = o.optString("space"),
                    ),
                    keys = keys,
                    udpSupported = if (o.isNull("udpSupported")) null else o.optBoolean("udpSupported"),
                    localKeys = l?.let {
                        LocalKeys(
                            mac = it.getString("mac"),
                            ip = it.optString("ip").takeIf { ip -> ip.isNotEmpty() },
                            keyHex = it.getString("key"),
                            ivHex = it.getString("iv"),
                        )
                    },
                    rawStatus = o.optJSONObject("status")?.toMap().orEmpty(),
                ).withModel(o.optString("model").takeIf { it.isNotEmpty() })
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_CODE_PREFIX = "miraie-local-v1:"

        /** Mac `ACKeys` field names ↔ capabilities. */
        val KEY_CODE_NAMES = listOf(
            "fan" to Capability.FanSpeed,
            "mode" to Capability.Mode,
            "power" to Capability.Power,
            "room" to Capability.RoomTemperature,
            "temp" to Capability.Temperature,
            "verticalSwing" to Capability.VerticalSwing,
        )

        /** JSON object string with keys sorted and nulls omitted (matches Swift `.sortedKeys`). */
        fun sortedJson(vararg pairs: Pair<String, Any?>): String =
            pairs.filter { it.second != null }.sortedBy { it.first }.joinToString(",", "{", "}") { (k, v) ->
                JSONObject.quote(k) + ":" + if (v is RawJson) v.json else JSONObject.quote(v.toString())
            }

        /** Pre-serialized JSON embedded as-is by [sortedJson]. */
        class RawJson(val json: String)
    }

}

/** Debug demo model: shapes follow MirAIe's capability attribute classes. */
private val DEMO_MODEL = """
{"modelId":"demo","controlCapabilities":[
 {"capability":"PowerStatus","attributes":[{"key":"ps","values":["on","off"]}]},
 {"capability":"VerticalSwing","attributes":[{"key":"acvs","range":{"min":[{"value":0}],"max":[{"value":5}],"increment":[{"value":1}]}}]},
 {"capability":"HorizontalSwing","attributes":[{"key":"achs","range":{"min":[{"value":0}],"max":[{"value":5}],"increment":[{"value":1}]}}]},
 {"capability":"DisplayControl","attributes":[{"key":"acdc","values":["on","night","off"]}]},
 {"capability":"ECOMode","attributes":[{"key":"acem","values":["on","off"]}]},
 {"capability":"PowerfulMode","attributes":[{"key":"acpm","values":["on","off"]}]},
 {"capability":"EvaporatorClean","attributes":[{"key":"acec","values":["on","off"]}]},
 {"capability":"NanoeG","attributes":[{"key":"acng","values":["on","off"]}]},
 {"capability":"Convertible","attributes":[{"key":"accnv","values":["40","50","60","70","80","90","100","110"]}]}],
 "statusCapabilitiesV2":[
 {"capability":"RoomHumidity","attributes":[{"key":"rmhmd"}]},
 {"capability":"FilterDustLevel","attributes":[{"key":"acfdl"}]},
 {"capability":"CurrentPowerConsumption","attributes":[{"key":"accpc"}]},
 {"capability":"ACFaults","attributes":[{"key":"acflt"}]}]}
"""

/** org.json → Kotlin map (values kept as org.json types). */
private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { k -> opt(k)?.takeIf { it != JSONObject.NULL } }
