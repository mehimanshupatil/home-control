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
    /** Experimental LAN control for models without `udpControl`. */
    val experimentalLocal: Boolean = false,
    val devices: List<AcState> = emptyList(),
    /** Local profiles per device id. */
    val profiles: Map<String, List<AcProfile>> = emptyMap(),
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
            experimentalLocal = store.experimentalLocal,
            devices = loadCache(),
        ).let { it.copy(profiles = it.devices.associate { d -> d.device.deviceId to loadProfiles(d.device.deviceId) }) },
    )
    val state: StateFlow<AcUiState> = _state.asStateFlow()

    private val localOnly get() = store.localOnly

    /** Whether LAN control may be attempted for [d]: model supports it, or the experimental switch is on. */
    private fun localAllowed(d: AcState) = d.udpSupported != false || store.experimentalLocal

    fun setExperimentalLocal(enabled: Boolean) {
        store.experimentalLocal = enabled
        _state.update { it.copy(experimentalLocal = enabled) }
        scope.launch { refresh() }
    }

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
            _state.update { s ->
                s.copy(
                    loading = false,
                    devices = devices,
                    profiles = devices.associate { d -> d.device.deviceId to loadProfiles(d.device.deviceId) },
                )
            }

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
    fun diagnosticsJson(appVersion: String): String = Diagnostics.build(appVersion, _state.value.devices, experimentalLocal = store.experimentalLocal)

    /** Debug builds only: sample ACs for UI screenshots (`adb shell am start ... --ez demo true`). Mirrors real model 130185. */
    fun seedDemo() {
        fun dev(id: String, name: String, space: String, model: String?) = MiraieDevice(id, name, model, "AC", "h/$id", "h", space)
        val now = System.currentTimeMillis() / 1000
        val status = JSONObject(DEMO_STATUS).put("actm", JSONArray(listOf(now + 95 * 60, -1))).toMap()
        val bedroom = AcState(
            dev("d1", "Bedroom AC", "Bedroom", "130185"), transport = Transport.Cloud, udpSupported = false,
            power = true, mode = "cool", temperature = 26.0, roomTemperature = 32.0, fanSpeed = "auto",
            localKeys = LocalKeys("AA:BB:CC:DD:EE:FF", "192.168.1.50", "00112233445566778899aabbccddeeff", "0f0e0d0c0b0a09080706050403020100"),
            rawStatus = status, lastLocalDiscovery = "not attempted",
        ).withModel(DEMO_MODEL)
        val living = AcState(
            dev("d2", "Living Room AC", "Living Room", null), transport = Transport.Cloud, power = false, mode = "dry",
            temperature = 25.5, roomTemperature = 30.0, fanSpeed = "low",
        )
        val profiles = listOf(
            AcProfile("Night", true, "cool", 26.0, "quiet", 0, 0, "off"),
            AcProfile("Quick cool", true, "cool", 22.0, "high", 1, 3, "on"),
        )
        _state.value = AcUiState(
            loggedIn = true,
            cloudConnected = !store.localOnly,
            localOnly = store.localOnly,
            experimentalLocal = store.experimentalLocal,
            devices = listOf(bedroom, living),
            profiles = mapOf("d1" to profiles),
        )
    }

    fun setPower(deviceId: String, on: Boolean) =
        send(deviceId, Capability.Power, if (on) "on" else "off") { it.copy(power = on) }

    fun setMode(deviceId: String, mode: String) =
        send(deviceId, Capability.Mode, mode) { it.copy(mode = mode) }

    fun setFanSpeed(deviceId: String, speed: String) =
        send(deviceId, Capability.FanSpeed, speed) { it.copy(fanSpeed = speed) }

    /** Clamps to the model's range and step; sends "26.0" like the official app (`DecimalFormat("#.0")`). */
    fun setTemperature(deviceId: String, temp: Double) {
        val core = _state.value.devices.firstOrNull { it.device.deviceId == deviceId }?.core ?: CoreSpec()
        val stepped = Math.round((temp - core.tempMin) / core.tempStep) * core.tempStep + core.tempMin
        val clamped = stepped.coerceIn(core.tempMin, core.tempMax)
        send(deviceId, Capability.Temperature, formatTemperature(clamped)) { it.copy(temperature = clamped) }
    }

    /**
     * Sets ([minutes] > 0) or cancels ([minutes] = -1) the off or on timer, keeping the other slot.
     * Payload is relative seconds; status is UTC seconds (PROTOCOL.md §1.8).
     */
    fun setTimer(deviceId: String, offSlot: Boolean, minutes: Int) {
        val ac = _state.value.devices.firstOrNull { it.device.deviceId == deviceId } ?: return
        val timer = ac.core.timer ?: return
        val now = System.currentTimeMillis() / 1000
        val payload = TimerMath.payload(offSlot, if (minutes > 0) minutes * 60L else -1L, ac.rawStatus[timer.key], now)
        val utc = JSONArray(TimerMath.toUtc(payload, now))
        sendKey(deviceId, timer.key, TimerSpec.KI, JSONArray(payload), statusValue = utc) { it }
    }

    // ---- local profiles -------------------------------------------------------------------------

    /** Saves the AC's current settings as a named profile (replaces one with the same name). */
    fun saveProfile(deviceId: String, name: String) {
        val ac = _state.value.devices.firstOrNull { it.device.deviceId == deviceId } ?: return
        fun int(spec: String) = ac.spec(spec)?.let { ac.statusOf(it)?.toString()?.toDoubleOrNull()?.toInt() }
        val profile = AcProfile(
            name = name.trim(),
            power = ac.power,
            mode = ac.mode,
            temperature = ac.temperature,
            fanSpeed = ac.fanSpeed,
            verticalSwing = int("VerticalSwing"),
            horizontalSwing = int("HorizontalSwing"),
            display = ac.spec("DisplayControl")?.let { ac.statusOf(it)?.toString() },
        )
        writeProfiles(deviceId, loadProfiles(deviceId).filter { it.name != profile.name } + profile)
    }

    fun deleteProfile(deviceId: String, name: String) =
        writeProfiles(deviceId, loadProfiles(deviceId).filter { it.name != name })

    /** Applies a profile: only differing settings, ~300 ms apart; power first when turning on, last when turning off. */
    fun applyProfile(deviceId: String, profile: AcProfile) {
        scope.launch {
            val ac = _state.value.devices.firstOrNull { it.device.deviceId == deviceId } ?: return@launch
            val steps = mutableListOf<() -> Unit>()
            if (profile.power && !ac.power) steps += { setPower(deviceId, true) }
            if (profile.power) {
                profile.mode?.takeIf { it != ac.mode && it in ac.core.modes }?.let { steps += { setMode(deviceId, it) } }
                profile.temperature?.takeIf { it != ac.temperature }?.let { steps += { setTemperature(deviceId, it) } }
                profile.fanSpeed?.takeIf { it != ac.fanSpeed && it in ac.core.fanSpeeds }?.let { steps += { setFanSpeed(deviceId, it) } }
                fun intStep(specName: String, value: Int?) {
                    val spec = ac.spec(specName) ?: return
                    if (value == null || ac.statusOf(spec)?.toString()?.toDoubleOrNull()?.toInt() == value) return
                    steps += { sendRaw(deviceId, spec.key, value, spec.ki) }
                }
                intStep("VerticalSwing", profile.verticalSwing)
                intStep("HorizontalSwing", profile.horizontalSwing)
                ac.spec("DisplayControl")?.let { spec ->
                    profile.display?.takeIf { it != ac.statusOf(spec)?.toString() }?.let { v -> steps += { sendRaw(deviceId, spec.key, v, spec.ki) } }
                }
            } else if (ac.power) {
                steps += { setPower(deviceId, false) }
            }
            steps.forEachIndexed { i, step ->
                if (i > 0) delay(300)
                step()
            }
        }
    }

    private fun loadProfiles(deviceId: String): List<AcProfile> = runCatching {
        val arr = JSONArray(store.acProfiles(deviceId) ?: return emptyList())
        (0 until arr.length()).map { AcProfile.fromJson(arr.getJSONObject(it)) }.filter { it.name.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun writeProfiles(deviceId: String, list: List<AcProfile>) {
        store.setAcProfiles(deviceId, JSONArray(list.map { it.toJson() }).toString())
        _state.update { it.copy(profiles = it.profiles + (deviceId to list)) }
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
        val localCandidates = devices.filter { it.localKeys != null && localAllowed(it) }
        val localStarted = localCandidates.isNotEmpty() && runCatching { local.start() }.isSuccess
        devices.map { d ->
            async {
                val attempt = localStarted && d.localKeys != null && localAllowed(d)
                val ip = if (attempt) {
                    local.register(d.device.deviceId, d.localKeys!!)
                    local.discover(d.device.deviceId, timeoutMs = 3000)
                } else {
                    null
                }
                val discovery = when {
                    !localAllowed(d) -> "not attempted"
                    d.localKeys == null -> "no key"
                    !attempt -> "not attempted"
                    ip != null -> "answered"
                    else -> "timeout"
                }
                val base = d.copy(lastLocalDiscovery = discovery)
                when {
                    ip != null -> {
                        persistIp(d.device.deviceId, ip)
                        base.copy(transport = Transport.Local, online = true)
                    }
                    localOnly -> base.copy(transport = Transport.Offline)
                    else -> base.copy(transport = Transport.Cloud)
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
        sendKey(deviceId, null, cap.ki, value, cap, optimistic = optimistic)

    private fun sendKey(
        deviceId: String,
        rawKey: String?,
        ki: Int,
        value: Any,
        cap: Capability? = null,
        statusValue: Any = value,
        optimistic: (AcState) -> AcState,
    ) {
        val ac = _state.value.devices.firstOrNull { it.device.deviceId == deviceId } ?: return
        val key = rawKey ?: ac.key(cap!!)
        updateDevice(deviceId) { optimistic(it).let { o -> o.copy(rawStatus = o.rawStatus + (key to statusValue)) } }
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

/** Debug demo model: the real model 130185 from user diagnostics (PROTOCOL.md §1.8). */
private val DEMO_MODEL = """
{"modelId":"130185","configCapabilities":[{"capability":"ProximityNotification"}],
 "controlCapabilities":[
 {"capability":"ACFanSpeed","attributes":[{"key":"acfs","values":["auto","quiet","low","medium","high"]}]},
 {"capability":"ACMode","attributes":[{"key":"acmd","values":["auto","cool","dry","fan"]}]},
 {"capability":"ACTemperature","attributes":[{"key":"actmp","range":{"increment":[{"unit":"celcius","value":1},{"unit":"fahrenheit","value":1}],"max":[{"unit":"celcius","value":30},{"unit":"fahrenheit","value":86}],"min":[{"unit":"celcius","value":16},{"unit":"fahrenheit","value":61}]}}]},
 {"capability":"ACTimer","attributes":[{"key":"actm","range":{"increment":[{"value":10}],"max":[{"value":1440}],"min":[{"value":10}]}}]},
 {"capability":"Convertible","attributes":[{"intValues":[110,100,90,80,70,55,40,0],"key":"cnv"}]},
 {"capability":"DisplayControl","attributes":[{"key":"acdc","values":["on","off"]}]},
 {"capability":"ECOMode","attributes":[{"key":"acem","values":["on","off"]}]},
 {"capability":"EvaporatorClean","attributes":[{"key":"acec"}]},
 {"capability":"HorizontalSwing","attributes":[{"key":"achs","range":{"increment":[{"value":1}],"max":[{"value":5}],"min":[{"value":0}]}}]},
 {"capability":"PowerStatus","attributes":[{"key":"ps"}]},
 {"capability":"PowerfulMode","attributes":[{"key":"acpm"}]},
 {"capability":"SleepProfile","attributes":[{"key":"acsp"}]},
 {"capability":"TimeBasedFilterHealth","attributes":[{"key":"acfh"}]},
 {"capability":"VerticalSwing","attributes":[{"key":"acvs","range":{"increment":[{"value":1}],"max":[{"value":5}],"min":[{"value":0}]}}]},
 {"capability":"BuzzerControl","attributes":[{"key":"bzr","minFwVersion":3.02,"values":["on","off"]}]}],
 "statusCapabilitiesV2":[{"capability":"ACFaults"},{"capability":"PowerConsumption"}]}
"""

/** Debug demo status: real keys from user diagnostics (actm is set at seed time). */
private val DEMO_STATUS = """
{"V":"3.02","acdc":"on","acec":"off","acem":"off","acfs":"auto","achs":0,"acmd":"cool","acpm":"off","acsp":"none",
 "actmp":"26.0","acvs":0,"bzr":"on","cnv":0,"errors":"","warnings":"","filterCleaningRequired":true,"filterDustLevel":2,
 "onlineStatus":"true","ps":"on","rmtmp":"32.0","rssi":-56,"ty":"AC"}
"""

/** Official temperature format (`DecimalFormat("#.0")`): "26.0", "24.5". */
internal fun formatTemperature(t: Double): String = String.format(java.util.Locale.US, "%.1f", t)

/** org.json → Kotlin map (values kept as org.json types). */
private fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { k -> opt(k)?.takeIf { it != JSONObject.NULL } }
