package `in`.canvs.homecontrol.miraie

import org.json.JSONArray
import org.json.JSONObject

/** How a model capability is presented and sent. */
enum class CapKind { Toggle, Choice, IntChoice, Status }

/** One capability the AC's model lists, resolved to its wire key and allowed values. */
data class CapabilitySpec(
    val name: String,
    val key: String,
    val ki: Int,
    val kind: CapKind,
    val label: String,
    /** String payload values (Choice). */
    val values: List<String> = emptyList(),
    /** Integer payload values (IntChoice). */
    val intValues: List<Int> = emptyList(),
)

/**
 * Capabilities beyond the core power/mode/temp/fan controls, from MirAIe's `BaseCapability.Type`
 * (key index = enum constant). See PROTOCOL.md §1.7.
 */
object CapabilityCatalog {

    private data class Info(val ki: Int, val kind: CapKind, val label: String)

    private val INFO = mapOf(
        "PowerfulMode" to Info(3, CapKind.Toggle, "Powerful"),
        "ECOMode" to Info(10, CapKind.Toggle, "ECO"),
        "EvaporatorClean" to Info(9, CapKind.Toggle, "Clean"),
        "NanoeG" to Info(7, CapKind.Toggle, "nanoe G"),
        "NanoeX" to Info(23, CapKind.Toggle, "nanoe X"),
        "JetStream" to Info(13, CapKind.Toggle, "Jet stream"),
        "TurboMode" to Info(18, CapKind.Toggle, "Turbo"),
        "Ionizer" to Info(16, CapKind.Toggle, "Ionizer"),
        "MotionSensor" to Info(17, CapKind.Toggle, "Motion sensor"),
        "EcoNavi" to Info(21, CapKind.Toggle, "ECONAVI"),
        "EconomySensor" to Info(27, CapKind.Toggle, "Economy sensor"),
        "BuzzerControl" to Info(30, CapKind.Toggle, "Beep"),
        "DisplayControl" to Info(8, CapKind.Choice, "Display"),
        "VerticalSwing" to Info(5, CapKind.IntChoice, "Vertical swing"),
        "HorizontalSwing" to Info(6, CapKind.IntChoice, "Horizontal swing"),
        "Convertible" to Info(28, CapKind.IntChoice, "Capacity"),
        "ACGenMode" to Info(19, CapKind.IntChoice, "Capacity"),
        "RoomHumidity" to Info(102, CapKind.Status, "Humidity"),
        "FilterDustLevel" to Info(15, CapKind.Status, "Filter"),
        "TimeBasedFilterHealth" to Info(25, CapKind.Status, "Filter health"),
        "PM25Sensor" to Info(20, CapKind.Status, "PM2.5"),
        "CurrentPowerConsumption" to Info(104, CapKind.Status, "Power"),
        "ACFaults" to Info(107, CapKind.Status, "Faults"),
    )

    /** Convertible capacity (%) → payload int. Off sends 0. */
    val CONVERTIBLE_DEFAULT = listOf(40, 50, 55, 60, 70, 80, 90, 100, 110, 0)

    /** ACGenMode capacity (%) → payload int, from `ACGenModeCapability.Type`. */
    val GEN_MODE_PAYLOAD = linkedMapOf(40 to 5, 60 to 4, 80 to 3, 100 to 2, 110 to 1, 0 to 0)

    /** Keeps only the model fields needed later (no secrets live in model data). */
    fun trimModel(model: JSONObject): JSONObject {
        val out = JSONObject()
        for (field in listOf("modelId", "modelName", "modelNumber", "controlCapabilities", "configCapabilities", "statusCapabilitiesV2")) {
            if (model.has(field)) out.put(field, model.get(field))
        }
        return out
    }

    /** Parses supported extra capabilities from a (trimmed) model JSON string. Order follows the catalogue. */
    fun parse(modelJson: String?): List<CapabilitySpec> {
        if (modelJson.isNullOrBlank()) return emptyList()
        val model = runCatching { JSONObject(modelJson) }.getOrNull() ?: return emptyList()
        val found = linkedMapOf<String, CapabilitySpec>()
        for (field in listOf("controlCapabilities", "statusCapabilitiesV2")) {
            val caps = model.optJSONArray(field) ?: continue
            for (i in 0 until caps.length()) {
                val cap = caps.optJSONObject(i) ?: continue
                val name = cap.optString("capability")
                val info = INFO[name] ?: continue
                if (found.containsKey(name)) continue
                found[name] = spec(name, info, cap.optJSONArray("attributes")?.optJSONObject(0))
            }
        }
        return INFO.keys.mapNotNull { found[it] }
    }

    private fun spec(name: String, info: Info, attr: JSONObject?): CapabilitySpec {
        val key = attr?.optString("key").orEmpty().ifEmpty { name }
        val strings = attr?.optJSONArray("values").strings()
        val ints = attr?.optJSONArray("intValues").ints().ifEmpty { attr?.optJSONObject("range")?.let(::expandRange).orEmpty() }
        return when (info.kind) {
            CapKind.Choice -> CapabilitySpec(name, key, info.ki, info.kind, info.label, values = strings.ifEmpty { listOf("on", "night", "off") })
            CapKind.IntChoice -> {
                val values = when (name) {
                    // Capacity values arrive as strings ("40") or ints; off is always offered.
                    "Convertible" -> (strings.mapNotNull { it.toIntOrNull() } + ints).distinct().ifEmpty { CONVERTIBLE_DEFAULT }
                    "ACGenMode" -> (strings.mapNotNull { it.toIntOrNull() } + ints).distinct().filter { it in GEN_MODE_PAYLOAD }
                        .ifEmpty { GEN_MODE_PAYLOAD.keys.toList() }
                    else -> ints.ifEmpty { (0..5).toList() }
                }.let { if (name == "Convertible" || name == "ACGenMode") (it - 0) + 0 else it }
                CapabilitySpec(name, key, info.ki, info.kind, info.label, intValues = values)
            }
            else -> CapabilitySpec(name, key, info.ki, info.kind, info.label)
        }
    }

    /** `range: { min: [{value}], max: [{value}], increment: [{value}] }` → every integer step. */
    private fun expandRange(range: JSONObject): List<Int> {
        fun first(field: String) = range.optJSONArray(field)?.optJSONObject(0)?.optDouble("value")?.takeIf { !it.isNaN() }
        val min = first("min") ?: return emptyList()
        val max = first("max") ?: return emptyList()
        val step = (first("increment") ?: 1.0).coerceAtLeast(1.0)
        if (max < min || (max - min) / step > 64) return emptyList()
        return generateSequence(min) { it + step }.takeWhile { it <= max + 1e-6 }.map { it.toInt() }.toList()
    }

    /** Payload for an IntChoice option (capacity % for ACGenMode maps to its enum int). */
    fun payload(spec: CapabilitySpec, option: Int): Int =
        if (spec.name == "ACGenMode") GEN_MODE_PAYLOAD[option] ?: option else option

    /** Whether [statusValue] (as reported by the AC) corresponds to [option]. */
    fun matches(spec: CapabilitySpec, option: Int, statusValue: Any?): Boolean {
        val text = statusValue?.toString() ?: return false
        val n = text.toDoubleOrNull()?.toInt()
        return when {
            text.equals("off", ignoreCase = true) -> option == 0
            spec.name == "ACGenMode" -> n == payload(spec, option) || n == option
            else -> n == option
        }
    }

    /** Human label for an IntChoice option. Swing: 0 = Auto (native app shows Auto for any value outside 1–5); 32 = Off. */
    fun optionLabel(spec: CapabilitySpec, option: Int): String = when (spec.name) {
        "VerticalSwing" -> if (option in 1..5) "$option" else "Auto"
        "HorizontalSwing" -> when (option) { 32 -> "Off"; in 1..5 -> "$option"; else -> "Auto" }
        "Convertible", "ACGenMode" -> if (option == 0) "Off" else "$option%"
        else -> "$option"
    }

    fun choiceLabel(value: String) = value.replaceFirstChar { it.uppercase() }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

    private fun JSONArray?.ints(): List<Int> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.toString()?.toDoubleOrNull()?.toInt() }
}
