package `in`.canvs.homecontrol.miraie

import `in`.canvs.homecontrol.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MiraieException(message: String, val code: Int = 0) : IOException(message)

/** REST client for the MirAIe cloud (see PROTOCOL.md §1). */
class MiraieApi(private val store: SecureStore) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val authMutex = Mutex()

    val isLoggedIn: Boolean get() = store.accessToken != null

    suspend fun login(login: String, password: String): Session {
        val body = JSONObject()
            .put("clientId", CLIENT_ID)
            .put("password", password)
            .put("scope", "an_" + (System.nanoTime() % 1_000_000_000L))
        if (login.contains('@')) {
            body.put("email", login.trim())
        } else {
            body.put("mobile", normalizeMobile(login))
        }
        val session = parseSession(post(AUTH_BASE + "userManagement/login", body, token = null))
        store.login = login
        store.password = password
        saveSession(session)
        return session
    }

    fun logout() = store.clearMiraie()

    suspend fun homes(): List<Home> {
        val arr = JSONArray(authedGet(API_BASE + "homeManagement/homes"))
        return (0 until arr.length()).map { i ->
            val h = arr.getJSONObject(i)
            val homeId = h.getString("homeId")
            val devices = mutableListOf<MiraieDevice>()
            val spaces = h.optJSONArray("spaces") ?: JSONArray()
            for (s in 0 until spaces.length()) {
                val space = spaces.getJSONObject(s)
                val spaceName = space.optString("spaceName")
                val spaceId = space.optString("spaceId")
                val ds = space.optJSONArray("devices") ?: continue
                for (d in 0 until ds.length()) {
                    val dev = ds.getJSONObject(d)
                    val deviceId = dev.getString("deviceId")
                    val topic = dev.optJSONArray("topic")?.optString(0)?.takeIf { it.isNotEmpty() }
                        ?: "$homeId/$deviceId"
                    devices += MiraieDevice(
                        deviceId = deviceId,
                        name = dev.optString("deviceName", "AC"),
                        modelId = dev.optString("modelId").takeIf { it.isNotEmpty() },
                        category = dev.optString("category").takeIf { it.isNotEmpty() },
                        topic = topic,
                        homeId = homeId,
                        space = spaceName,
                        spaceId = spaceId,
                    )
                }
            }
            val spaceList = (0 until spaces.length()).mapNotNull { spaces.optJSONObject(it) }
                .map { Space(it.optString("spaceId"), it.optString("spaceName")) }
            Home(homeId, h.optString("homeName", "Home"), devices, spaceList)
        }
    }

    /**
     * Maps capabilities to their JSON keys from `controlCapabilities[].attributes[0].key`, and reports
     * whether `configCapabilities` lists `udpControl` (LAN control support).
     */
    /** Core capability keys, `udpControl` support, and the trimmed model JSON (for extra capabilities). */
    data class ModelResult(val keys: Map<Capability, String>, val udp: Boolean, val rawJson: String)

    suspend fun modelInfo(modelId: String): ModelResult {
        val json = JSONObject(authedGet(API_BASE + "deviceManagement/deviceModels/modelId/$modelId"))
        val result = Capability.entries.associateWith { it.defaultKey }.toMutableMap()
        for (field in listOf("controlCapabilities", "statusCapabilitiesV2")) {
            val caps = json.optJSONArray(field) ?: continue
            for (i in 0 until caps.length()) {
                val cap = caps.optJSONObject(i) ?: continue
                val c = Capability.entries.firstOrNull { it.modelName == cap.optString("capability") } ?: continue
                val key = cap.optJSONArray("attributes")?.optJSONObject(0)?.optString("key")
                if (!key.isNullOrEmpty()) result[c] = key
            }
        }
        val config = json.optJSONArray("configCapabilities") ?: JSONArray()
        val udp = (0 until config.length()).any { config.optJSONObject(it)?.optString("capability") == "udpControl" }
        return ModelResult(result, udp, CapabilityCatalog.trimModel(json).toString())
    }

    /** One-time fetch of LAN secrets: `[{ macAddress, ipAddress, udpControlKey, udpcontrolIV }]`. */
    suspend fun localKeys(deviceId: String): LocalKeys? {
        val text = authedGet(API_BASE + "deviceManagement/devices/deviceId/$deviceId")
        val obj = runCatching { JSONArray(text).optJSONObject(0) }.getOrNull()
            ?: runCatching { JSONObject(text) }.getOrNull() ?: return null
        val mac = obj.optString("macAddress")
        val key = obj.optString("udpControlKey")
        val iv = obj.optString("udpcontrolIV").ifEmpty { obj.optString("udpControlIV") }
        if (mac.isEmpty() || key.isEmpty() || iv.isEmpty()) return null
        return LocalKeys(mac = mac, ip = obj.optString("ipAddress").takeIf { it.isNotEmpty() }, keyHex = key, ivHex = iv)
    }

    suspend fun status(deviceId: String): JSONObject =
        JSONObject(authedGet(API_BASE + "deviceManagement/devices/$deviceId/mobile/status"))

    // ---- cloud features (docs/FEATURES.md) -----------------------------------------------------------

    /** `GET powerConsumption/devices/{id}?grain&startDate&endDate` → `[{day|week|month, power}]`. */
    suspend fun energy(deviceId: String, window: EnergyWindow): JSONArray =
        JSONArray(authedGet(API_BASE + "powerConsumption/devices/$deviceId?grain=${window.grain.api}&startDate=${window.startDate}&endDate=${window.endDate}"))

    private fun sleepPath(d: MiraieDevice) =
        API_BASE + "homeManagement/homes/${d.homeId}/spaces/${d.spaceId}/devices/${d.deviceId}/sleepProfile"

    suspend fun sleepProfiles(d: MiraieDevice): List<SleepProfile> {
        val arr = JSONArray(authedGet(sleepPath(d)))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(SleepProfile::fromJson)
    }

    suspend fun createSleepProfile(d: MiraieDevice, p: SleepProfile): SleepProfile =
        SleepProfile.fromJson(JSONObject(authed("POST", sleepPath(d), p.createBody()).ifEmpty { "{}" })).let { r -> if (r.id == null) p else r }

    suspend fun updateSleepProfile(d: MiraieDevice, p: SleepProfile): SleepProfile =
        SleepProfile.fromJson(JSONObject(authed("PUT", sleepPath(d) + "/${p.id}", p.updateBody()).ifEmpty { "{}" })).let { r -> if (r.id == null) p else r }

    suspend fun deleteSleepProfile(d: MiraieDevice, profileId: String) {
        authed("DELETE", sleepPath(d) + "/$profileId", null)
    }

    /** `POST rms/homes/{h}/rules` → `{status, ruleId}`. */
    suspend fun createRule(homeId: String, body: JSONObject): String =
        JSONObject(authed("POST", API_BASE + "rms/homes/$homeId/rules", body)).optString("ruleId")

    suspend fun updateRule(homeId: String, ruleId: String, body: JSONObject): String =
        JSONObject(authed("PUT", API_BASE + "rms/homes/$homeId/rules/$ruleId", body).ifEmpty { "{}" }).optString("ruleId").ifEmpty { ruleId }

    suspend fun rule(homeId: String, ruleId: String): JSONObject =
        JSONObject(authedGet(API_BASE + "rms/homes/$homeId/rules/$ruleId"))

    suspend fun deleteRule(homeId: String, ruleId: String) {
        authed("DELETE", API_BASE + "rms/homes/$homeId/rules/$ruleId", null)
    }

    suspend fun pauseRule(homeId: String, body: JSONArray) {
        authed("PUT", API_BASE + "rms/homes/$homeId/rules/pause", body)
    }

    suspend fun resumeRule(homeId: String, ruleId: String) {
        authed("PUT", API_BASE + "rms/homes/$homeId/rules/resume", JSONArray().put(ruleId))
    }

    /** Time-based filter reset (`PUT deviceManagement/devices/{id}/filterDustLevelInfo`, no body). */
    suspend fun resetFilter(deviceId: String) {
        authed("PUT", API_BASE + "deviceManagement/devices/$deviceId/filterDustLevelInfo", null)
    }

    /**
     * REST control, exactly as `GeofenceBroadcastReceiver` sends it: `POST deviceManagement/control`
     * `{deviceId, control_params:{...fields, "cnt":"gva", "ki":N, "sid":"11"}}`. Only `ps` is observed in the
     * official app; other keys through this endpoint are unverified.
     */
    suspend fun control(deviceId: String, fields: JSONObject, ki: Int) {
        val params = JSONObject(fields.toString())
        if (ki >= 0) params.put("ki", ki)
        params.put("cnt", "gva").put("sid", "11")
        authed("POST", API_BASE + "deviceManagement/control", JSONObject().put("deviceId", deviceId).put("control_params", params))
    }

    suspend fun renameDevice(d: MiraieDevice, name: String) {
        authed("PUT", API_BASE + "homeManagement/homes/${d.homeId}/spaces/${d.spaceId}/devices/${d.deviceId}", JSONObject().put("deviceName", name))
    }

    suspend fun moveDevice(d: MiraieDevice, newSpaceId: String) {
        authed("POST", API_BASE + "homeManagement/homes/${d.homeId}/spaces/${d.spaceId}/devices/${d.deviceId}", JSONObject().put("newSpaceId", newSpaceId))
    }

    suspend fun firmware(deviceId: String, currentVersion: String): FirmwareInfo =
        FirmwareInfo.fromJson(JSONObject(authedGet(API_BASE + "fota/firmware/deviceId/$deviceId/download?currentVersion=" + java.net.URLEncoder.encode(currentVersion, "UTF-8"))))

    /** `notificationmanager/log?severity&pgIndex&pgSize` (pages start at 1). */
    suspend fun notifications(severity: String, page: Int, pageSize: Int = 20): List<AcNotification> {
        val arr = JSONArray(authedGet(API_BASE + "notificationmanager/log?severity=$severity&pgIndex=$page&pgSize=$pageSize"))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(AcNotification::fromJson)
    }

    suspend fun unreadAlerts(): Int =
        JSONObject(authedGet(API_BASE + "notificationmanager/unexpired/count?severity=alert")).optInt("unexpired", 0)

    /** Returns a valid access token, refreshing or re-logging in when it has expired. */
    suspend fun validToken(): String = authMutex.withLock {
        val token = store.accessToken
        if (token != null && System.currentTimeMillis() < store.expiresAt - 60_000) return token
        renewLocked()
    }

    private suspend fun forceRenew(): String = authMutex.withLock { renewLocked() }

    private suspend fun renewLocked(): String {
        store.refreshToken?.let { refresh ->
            try {
                val body = JSONObject().put("clientId", CLIENT_ID).put("token", refresh)
                val session = parseSession(post(AUTH_BASE + "userManagement/tokenRefresh", body, token = null))
                saveSession(session)
                return session.accessToken
            } catch (_: IOException) {
                // fall through to full login
            }
        }
        val login = store.login
        val password = store.password
        if (login == null || password == null) throw MiraieException("Not logged in", 401)
        return login(login, password).accessToken
    }

    private suspend fun authedGet(url: String): String {
        return try {
            get(url, validToken())
        } catch (e: MiraieException) {
            if (e.code != 401) throw e
            get(url, forceRenew())
        }
    }

    /** Authenticated POST/PUT/DELETE with a JSON body (object or array), retrying once after a 401. */
    private suspend fun authed(method: String, url: String, body: Any?): String {
        suspend fun call(token: String) = withContext(Dispatchers.IO) {
            val rb = (body?.toString() ?: if (method == "DELETE") null else "").let { it?.toRequestBody(JSON) }
            execute(Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, rb).build())
        }
        return try {
            call(validToken())
        } catch (e: MiraieException) {
            if (e.code != 401) throw e
            call(forceRenew())
        }
    }

    private suspend fun get(url: String, token: String): String = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build())
    }

    private suspend fun post(url: String, body: JSONObject, token: String?): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody(JSON))
        if (token != null) builder.header("Authorization", "Bearer $token")
        execute(builder.build())
    }

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotEmpty() } ?: "HTTP ${resp.code}"
                throw MiraieException(msg, resp.code)
            }
            return text
        }
    }

    private fun parseSession(text: String): Session {
        val j = JSONObject(text)
        return Session(
            accessToken = j.getString("accessToken"),
            refreshToken = j.optString("refreshToken"),
            userId = j.optString("userId"),
            expiresInSeconds = j.optLong("expiresIn", 3600L),
        )
    }

    private fun saveSession(s: Session) {
        store.accessToken = s.accessToken
        if (s.refreshToken.isNotEmpty()) store.refreshToken = s.refreshToken
        if (s.userId.isNotEmpty()) store.userId = s.userId
        store.expiresAt = System.currentTimeMillis() + s.expiresInSeconds * 1000
    }

    private fun normalizeMobile(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.trim().startsWith("+") -> "+$digits"
            digits.length == 10 -> "+91$digits"
            else -> "+$digits"
        }
    }

    companion object {
        const val AUTH_BASE = "https://auth.miraie.in/simplifi/v1/"
        const val API_BASE = "https://app.miraie.in/simplifi/v1/"
        const val CLIENT_ID = "292d99d7-5623-4157-8a71-846ee3ff5dbf"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
