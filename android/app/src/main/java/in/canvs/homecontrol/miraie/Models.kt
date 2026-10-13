package `in`.canvs.homecontrol.miraie

/** Capability names from the MirAIe device model, with the app's fixed key index (`ki`) and fallback JSON key. */
enum class Capability(val modelName: String, val ki: Int, val defaultKey: String) {
    Power("PowerStatus", 1, "ps"),
    Mode("ACMode", 0, "acmd"),
    Temperature("ACTemperature", 2, "actmp"),
    FanSpeed("ACFanSpeed", 4, "acfs"),
    VerticalSwing("VerticalSwing", 5, "acvs"),
    RoomTemperature("RoomTemperature", 101, "rmtmp"),
}

val AC_MODES = listOf("auto", "cool", "heat", "dry", "fan")
val FAN_SPEEDS = listOf("auto", "quiet", "low", "medium", "high")

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val expiresInSeconds: Long,
)

data class MiraieDevice(
    val deviceId: String,
    val name: String,
    val modelId: String?,
    val category: String?,
    val topic: String,
    val homeId: String,
    /** Room ("space") name from the MirAIe home. */
    val space: String = "",
)

data class Home(val homeId: String, val name: String, val devices: List<MiraieDevice>)

/** Per-device secrets for LAN control, fetched once from `deviceManagement/devices/deviceId/{id}`. */
data class LocalKeys(
    val mac: String,
    val ip: String?,
    val keyHex: String,
    val ivHex: String,
)

enum class Transport { Local, Cloud, Offline }

data class AcState(
    val device: MiraieDevice,
    val keys: Map<Capability, String> = Capability.entries.associateWith { it.defaultKey },
    /** null = model fetch failed (try local anyway); false = model has no `udpControl`. */
    val udpSupported: Boolean? = null,
    val localKeys: LocalKeys? = null,
    val transport: Transport = Transport.Offline,
    val online: Boolean = true,
    val power: Boolean = false,
    val mode: String = "cool",
    val temperature: Double = 24.0,
    val fanSpeed: String = "auto",
    val roomTemperature: Double? = null,
    /** Trimmed device-model JSON (capabilities only); null until fetched once from the cloud. */
    val modelJson: String? = null,
    /** Extra capabilities the model lists, parsed from [modelJson]. */
    val capabilities: List<CapabilitySpec> = CapabilityCatalog.parse(modelJson),
    /** Latest status fields merged from REST, MQTT and local UDP (raw keys → values). */
    val rawStatus: Map<String, Any?> = emptyMap(),
) {
    fun key(c: Capability): String = keys[c] ?: c.defaultKey

    /** Sets the model JSON and re-derives [capabilities] (data-class `copy` would not). */
    fun withModel(json: String?): AcState = copy(modelJson = json, capabilities = CapabilityCatalog.parse(json))

    /** Raw status value for a capability's key, if the AC has reported it. */
    fun statusOf(spec: CapabilitySpec): Any? = rawStatus[spec.key]
}
