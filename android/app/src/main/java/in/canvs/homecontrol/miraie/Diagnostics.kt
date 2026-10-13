package `in`.canvs.homecontrol.miraie

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shareable diagnostics: model capabilities and last raw status per AC.
 * Never contains credentials or tokens; LAN key and IV are masked to their first 4 hex characters.
 */
object Diagnostics {

    /** Field names that must never appear with a value, wherever they occur in raw data. */
    private val SECRET_FIELDS = setOf(
        "password", "accesstoken", "refreshtoken", "token", "udpcontrolkey", "udpcontroliv",
        "deviceregistrationtokenencrypted", "clientsecret",
    )

    fun mask(hex: String?): String? = hex?.let { if (it.length <= 4) "..." else it.take(4) + "..." } // ASCII: org.json would escape "…"

    fun build(
        appVersion: String,
        devices: List<AcState>,
        generatedAtMillis: Long = System.currentTimeMillis(),
        experimentalLocal: Boolean = false,
    ): String {
        val arr = JSONArray()
        devices.forEach { d ->
            val caps = JSONArray()
            d.capabilities.forEach { c ->
                caps.put(
                    JSONObject()
                        .put("name", c.name).put("key", c.key).put("ki", c.ki).put("kind", c.kind.name)
                        .put("values", JSONArray(c.values)).put("intValues", JSONArray(c.intValues)),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", d.device.deviceId)
                    .put("name", d.device.name)
                    .put("modelId", d.device.modelId ?: JSONObject.NULL)
                    .put("udpSupported", d.udpSupported ?: JSONObject.NULL)
                    .put("transport", d.transport.name)
                    .put("online", d.online)
                    .put("firmware", d.rawStatus["V"]?.toString() ?: JSONObject.NULL)
                    .put("lanKeyAvailable", d.localKeys != null)
                    .put("lastLocalDiscovery", d.lastLocalDiscovery)
                    .put(
                        "lan",
                        d.localKeys?.let {
                            JSONObject().put("mac", it.mac).put("ip", it.ip ?: JSONObject.NULL)
                                .put("key", mask(it.keyHex)).put("iv", mask(it.ivHex))
                        } ?: JSONObject.NULL,
                    )
                    .put("coreKeys", JSONObject(d.keys.mapKeys { it.key.name }))
                    .put("capabilities", caps)
                    .put("model", d.modelJson?.let { scrub(JSONObject(it)) } ?: JSONObject.NULL)
                    .put("status", scrub(JSONObject(d.rawStatus.mapValues { it.value ?: JSONObject.NULL }))),
            )
        }
        return JSONObject()
            .put("app", "Home Control $appVersion")
            .put("generatedAt", generatedAtMillis)
            .put("experimentalLocal", experimentalLocal)
            .put("note", "No password, tokens or full LAN keys are included.")
            .put("acs", arr)
            .toString(2)
    }

    /** Recursively removes secret-looking fields from raw server/device JSON. */
    private fun scrub(value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out ->
            value.keys().forEach { k ->
                if (k.lowercase() !in SECRET_FIELDS) out.put(k, scrub(value.get(k)))
            }
        }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(scrub(value.get(i))) }
        else -> value
    }
}
