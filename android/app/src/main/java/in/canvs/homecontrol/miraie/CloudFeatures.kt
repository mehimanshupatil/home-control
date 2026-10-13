package `in`.canvs.homecontrol.miraie

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure helpers for the MirAIe cloud features (docs/FEATURES.md). Each mirrors a decompiled class,
// cited per item, so the payloads can be unit-tested without a device.

// ---- sleep profiles (ACProfile / SleepProfileEntity / ACProfileCapability) -------------------------

/**
 * A MirAIe sleep profile: one target temperature per hour. [offAtEnd] adds the trailing "00.0" entry
 * that tells the AC to switch off when the profile ends. The built-in profile is named "default".
 */
data class SleepProfile(
    val id: String?,
    val name: String,
    val temps: List<Double>,
    val offAtEnd: Boolean,
    val ruleIds: List<String> = emptyList(),
) {
    val isDefault: Boolean get() = name.equals(DEFAULT_NAME, ignoreCase = true)

    /** Display name: the built-in profile is called "Regular" in the official app. */
    val displayName: String get() = if (isDefault) "Regular" else name

    /** `SleepProfileEntity` temperatures: `%04.1f` per hour, plus "00.0" when switching off at the end. */
    fun temperatureStrings(): List<String> {
        val out = temps.map { String.format(Locale.ENGLISH, "%04.1f", it) }.toMutableList()
        if (offAtEnd && temps.lastOrNull() != 0.0) out += "00.0"
        return out
    }

    /** POST body (`SleepProfileEntity`; `profileId` omitted when new, `smartRuleId` left to the server). */
    fun createBody(): JSONObject = JSONObject().apply {
        if (!id.isNullOrEmpty()) put("profileId", id)
        put("profileName", name)
        put("temperatures", JSONArray(temperatureStrings()))
    }

    /** PUT body (`SleepProfileRequestEntity`). */
    fun updateBody(): JSONObject = JSONObject()
        .put("profileName", name)
        .put("temperatures", JSONArray(temperatureStrings()))

    /** `pid` sent to the AC: "default" for the built-in profile, otherwise the server id. */
    val pid: String get() = if (isDefault) DEFAULT_NAME else id.orEmpty()

    /**
     * Device command from `ACProfileCapability.buildMessage(profile, on)`:
     * `{"acsp":"on|off","ki":ki,"pid":id,"temps":[...]}`. The key is always `acsp` (hard-coded in the app).
     */
    fun activationFields(on: Boolean): JSONObject = JSONObject()
        .put(KEY, if (on) "on" else "off")
        .put("pid", pid)
        .put("temps", JSONArray(temperatureStrings()))

    /** Rule action payload string from `ACProfileCapability.buildPayloadForRule` (no temps). */
    fun rulePayload(ki: Int): String = JSONObject().put(KEY, "on").put("ki", ki).put("pid", pid).toString()

    /** Whether the AC's `acsp` status value means this profile is running. */
    fun isActive(acsp: String?): Boolean {
        val v = acsp?.trim().orEmpty()
        if (v.isEmpty() || v.equals("none", true) || v.equals("off", true)) return false
        return v.equals(id, ignoreCase = true) || (isDefault && v.equals(DEFAULT_NAME, ignoreCase = true))
    }

    companion object {
        const val KEY = "acsp"
        const val KI = 12
        const val DEFAULT_NAME = "default"
        const val MAX_CUSTOM = 3

        /** Parses one `SleepProfileEntity`; a trailing 0 temperature becomes [offAtEnd] (as `ACProfile`). */
        fun fromJson(o: JSONObject): SleepProfile {
            val raw = o.optJSONArray("temperatures") ?: JSONArray()
            val values = (0 until raw.length()).mapNotNull { raw.opt(it)?.toString()?.toDoubleOrNull() }
            val off = values.isNotEmpty() && values.last() == 0.0
            val rules = o.optJSONArray("smartRuleId")
            return SleepProfile(
                id = o.optString("profileId").ifEmpty { null },
                name = o.optString("profileName"),
                temps = if (off) values.dropLast(1) else values,
                offAtEnd = off,
                ruleIds = if (rules == null) emptyList() else (0 until rules.length()).mapNotNull { rules.optString(it).ifEmpty { null } },
            )
        }

        /** Default list order: built-in profile first (as `ACDevice.updateProfiles`). */
        fun sorted(list: List<SleepProfile>) = list.sortedBy { if (it.isDefault) 0 else 1 }
    }
}

/** SleepProfile attribute ranges from the model: `duration` (hours) and `temps` (°C). */
data class SleepSpec(
    val minHours: Int = 2,
    val maxHours: Int = 8,
    val minTemp: Double = 16.0,
    val maxTemp: Double = 30.0,
) {
    companion object {
        fun parse(modelJson: String?): SleepSpec? {
            val model = modelJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val caps = model.optJSONArray("controlCapabilities") ?: return null
            val cap = (0 until caps.length()).mapNotNull { caps.optJSONObject(it) }
                .firstOrNull { it.optString("capability") == "SleepProfile" } ?: return null
            val attrs = cap.optJSONArray("attributes") ?: JSONArray()
            fun attr(key: String) = (0 until attrs.length()).mapNotNull { attrs.optJSONObject(it) }
                .firstOrNull { it.optString("key").equals(key, ignoreCase = true) }?.optJSONObject("range")
            fun first(range: JSONObject?, field: String, unitPrefix: String? = null): Double? {
                val arr = range?.optJSONArray(field) ?: return null
                val entries = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                val e = unitPrefix?.let { p -> entries.firstOrNull { it.optString("unit").lowercase().startsWith(p) } } ?: entries.firstOrNull()
                return e?.optDouble("value")?.takeIf { !it.isNaN() }
            }
            val d = SleepSpec()
            val duration = attr("duration")
            val temps = attr("temps")
            return SleepSpec(
                minHours = first(duration, "min")?.toInt() ?: d.minHours,
                maxHours = first(duration, "max")?.toInt() ?: d.maxHours,
                minTemp = first(temps, "min", "cel") ?: d.minTemp,
                maxTemp = first(temps, "max", "cel") ?: d.maxTemp,
            )
        }
    }
}

// ---- schedules (rms rules: RulePayload / RuleItem.TimerTrigger) ----------------------------------

/** A weekly time trigger. [days] uses 1 = Sunday … 7 = Saturday (java Calendar order, as the app). */
data class Schedule(val hour: Int, val minute: Int, val days: Set<Int>) {
    /** `RuleTrigger.value`: HHMM as an int, e.g. 22:30 → 2230. */
    val hhmm: Int get() = hour * 100 + minute

    /** `repeatType`: "daily" (all days), "once" (none) or "S,M,T,W,TH,F,SA" codes in day order. */
    val repeatType: String
        get() = when {
            days.size == 7 -> "daily"
            days.isEmpty() -> "once"
            else -> days.sorted().joinToString(",") { DAY_CODES[it - 1] }
        }

    companion object {
        val DAY_CODES = listOf("S", "M", "T", "W", "TH", "F", "SA")
        val DAY_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

        fun parse(value: Int, repeatType: String?): Schedule {
            val rt = repeatType.orEmpty()
            val days = when (rt) {
                "daily" -> (1..7).toSet()
                "once", "" -> emptySet()
                else -> rt.split(",").mapNotNull { code -> DAY_CODES.indexOf(code.trim()).takeIf { it >= 0 }?.plus(1) }.toSet()
            }
            return Schedule(value / 100, value % 100, days)
        }
    }
}

/** A sleep-profile rule as returned by `GET rms/homes/{h}/rules/{id}`. */
data class SleepRule(val ruleId: String, val schedule: Schedule, val status: String) {
    val enabled: Boolean get() = status.equals("ENABLE", ignoreCase = true)

    companion object {
        fun fromJson(ruleId: String, o: JSONObject): SleepRule? {
            val trigger = o.optJSONArray("triggers")?.optJSONArray(0)?.optJSONObject(0)
                ?: o.optJSONObject("trigger") ?: return null
            return SleepRule(
                ruleId = o.optString("ruleId").ifEmpty { ruleId },
                schedule = Schedule.parse(trigger.optInt("value"), trigger.optString("repeatType")),
                status = o.optString("ruleStatus", "ENABLE"),
            )
        }
    }
}

object RulePayloads {
    /**
     * Body for `POST/PUT rms/homes/{homeId}/rules[/{ruleId}]` scheduling a sleep profile, built like
     * `RuleItem.getRulePayload()` with one `SleepProfileRuleAction` and one `TimerTrigger`.
     */
    fun sleepRule(
        homeId: String,
        userId: String,
        ruleName: String,
        deviceId: String,
        profile: SleepProfile,
        ki: Int,
        schedule: Schedule,
        ruleId: String? = null,
    ): JSONObject = JSONObject().apply {
        if (ruleId != null) put("ruleId", ruleId)
        put("ruleName", ruleName)
        put("homeId", homeId)
        put("userId", userId)
        put("primaryUserId", userId)
        put("ruleType", "User")
        put("schemaVersion", "v2")
        put("ruleStatus", "ENABLE")
        put(
            "triggers",
            JSONArray().put(
                JSONArray().put(
                    JSONObject()
                        .put("type", "Timer")
                        .put("repeatType", schedule.repeatType)
                        .put("check", "EQ")
                        .put("property", "Time")
                        .put("value", schedule.hhmm),
                ),
            ),
        )
        put(
            "rule",
            JSONObject()
                .put("sendPushNotification", true)
                .put(
                    "actions",
                    JSONArray().put(
                        JSONObject()
                            .put("deviceId", deviceId)
                            .put("profileId", profile.id.orEmpty())
                            .put("payload", profile.rulePayload(ki))
                            .put("payloadVersion", "0.1"),
                    ),
                ),
        )
    }

    /** `PUT rms/homes/{h}/rules/pause` body (`RuleService.pauseRule`): ms timestamps as strings, no end = indefinite. */
    fun pause(ruleId: String, startMillis: Long, endMillis: Long? = null): JSONArray = JSONArray().put(
        JSONObject().put("ruleId", ruleId).put("action", "pause").put("startTime", startMillis.toString())
            .apply { if (endMillis != null && endMillis != 0L) put("endTime", endMillis.toString()) },
    )

    /** Rule name: "Schedule <name>" for custom profiles, "Regular" for the built-in one (same as the Mac app). */
    fun ruleName(profile: SleepProfile) = if (profile.isDefault) "Regular" else "Schedule ${profile.name}"
}

// ---- energy (EnergyConsumption / DayHelper) -------------------------------------------------------

enum class EnergyGrain(val api: String, val label: String) {
    Daily("Daily", "Day"),
    Weekly("Weekly", "Week"),
    Monthly("Monthly", "Month"),
}

/** One bar: [key] matches the response `day`/`week`/`month` value; [label] is for display. */
data class EnergyBucket(val key: String, val label: String, val kwh: Double = 0.0)

data class EnergyWindow(val grain: EnergyGrain, val startDate: String, val endDate: String, val buckets: List<EnergyBucket>)

object EnergyMath {
    private val DAY = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.US)
    private val MONTH = DateTimeFormatter.ofPattern("MMyyyy", Locale.US)
    private val DAY_LABEL = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val WEEK_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.US)
    private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMM", Locale.US)

    /**
     * Request window and empty buckets: 7 days ending today, 7 weeks (week starts 7 days apart, the last
     * one starting 6 days ago), or 7 months ending this month. Dates are `ddMMyyyy`, months `MMyyyy`.
     */
    fun window(grain: EnergyGrain, today: LocalDate): EnergyWindow = when (grain) {
        EnergyGrain.Daily -> {
            val start = today.minusDays(6)
            val days = (0..6).map { start.plusDays(it.toLong()) }
            EnergyWindow(grain, DAY.format(start), DAY.format(today), days.map { EnergyBucket(DAY.format(it), DAY_LABEL.format(it)) })
        }
        EnergyGrain.Weekly -> {
            val lastStart = today.minusDays(6)
            val first = lastStart.minusWeeks(6)
            val weeks = (0..6).map { first.plusWeeks(it.toLong()) }
            EnergyWindow(grain, DAY.format(first), DAY.format(lastStart), weeks.map { EnergyBucket(DAY.format(it), WEEK_LABEL.format(it)) })
        }
        EnergyGrain.Monthly -> {
            // DayHelper.setupMonthData: this month and the 6 before it.
            val end = YearMonth.from(today)
            val start = end.minusMonths(6)
            val months = (0..6).map { start.plusMonths(it.toLong()) }
            EnergyWindow(grain, MONTH.format(start), MONTH.format(end), months.map { EnergyBucket(MONTH.format(it), MONTH_LABEL.format(it)) })
        }
    }

    /** Fills [window] buckets from `[{day|week|month, power}]`; missing buckets stay 0 (as the official chart). */
    fun fill(window: EnergyWindow, response: JSONArray): EnergyWindow {
        val field = when (window.grain) {
            EnergyGrain.Daily -> "day"
            EnergyGrain.Weekly -> "week"
            EnergyGrain.Monthly -> "month"
        }
        val values = HashMap<String, Double>()
        for (i in 0 until response.length()) {
            val o = response.optJSONObject(i) ?: continue
            val k = o.optString(field).ifEmpty { o.optString("day").ifEmpty { o.optString("week").ifEmpty { o.optString("month") } } }
            val v = o.opt("power")?.toString()?.toDoubleOrNull() ?: continue
            if (k.isNotEmpty()) values[k] = v
        }
        return window.copy(buckets = window.buckets.map { b -> b.copy(kwh = values[b.key] ?: 0.0) })
    }
}

// ---- filter, air quality ---------------------------------------------------------------------------

/** Time-based filter health from `TimeBasedFilterHealth` ranges (reminder/warning/alert hours). */
data class FilterHealth(val hours: Int, val reminder: Int, val warning: Int, val alert: Int) {
    enum class Level { Good, Reminder, Warning, Alert }

    val level: Level
        get() = when {
            hours >= alert -> Level.Alert
            hours >= warning -> Level.Warning
            hours >= reminder -> Level.Reminder
            else -> Level.Good
        }

    companion object {
        /** Reads thresholds from the model's TimeBasedFilterHealth attribute (defaults 100/200/500 h). */
        fun thresholds(modelJson: String?): Triple<Int, Int, Int>? {
            val model = modelJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val caps = model.optJSONArray("controlCapabilities") ?: return null
            val cap = (0 until caps.length()).mapNotNull { caps.optJSONObject(it) }
                .firstOrNull { it.optString("capability") == "TimeBasedFilterHealth" } ?: return null
            val range = cap.optJSONArray("attributes")?.optJSONObject(0)?.optJSONObject("range")
            fun v(f: String, d: Int) = range?.optJSONArray(f)?.optJSONObject(0)?.optInt("value", d) ?: d
            return Triple(v("reminder", 100), v("warning", 200), v("alert", 500))
        }
    }
}

/** PM2.5 bands from `ACDevice.getAQIPM25Level`. */
object AirQuality {
    enum class Band(val label: String) { Excellent("Excellent"), Good("Good"), Moderate("Moderate"), Poor("Poor"), Unhealthy("Unhealthy"), Hazardous("Hazardous") }

    fun band(pm: Int): Band? = when {
        pm < 0 -> null
        pm <= 30 -> Band.Excellent
        pm <= 60 -> Band.Good
        pm <= 90 -> Band.Moderate
        pm <= 120 -> Band.Poor
        pm <= 250 -> Band.Unhealthy
        else -> Band.Hazardous
    }
}

// ---- official side effects (ACDevice setters) --------------------------------------------------------

/** One device command: fields plus key index. */
data class Command(val fields: JSONObject, val ki: Int) {
    val key: String get() = fields.keys().next()

    companion object {
        fun of(key: String, value: Any, ki: Int) = Command(JSONObject().put(key, value), ki)
    }
}

/**
 * Reproduces the extra commands the official app sends around a change (`ACDevice.setPowerStatus`,
 * `setEcoMode`, `setACPowerfulMode`, `setACJetStreamMode`, `setTurboMode`, `setACMode`, and
 * `resetSleepProfileIfNeeded`). Returns commands in the official order.
 */
object SideEffects {

    /** The active, non-default sleep profile (from status `acsp`), if one must be switched off. */
    fun activeCustomProfile(ac: AcState, profiles: List<SleepProfile>): SleepProfile? {
        val acsp = ac.rawStatus[SleepProfile.KEY]?.toString()
        return profiles.firstOrNull { it.isActive(acsp) }?.takeIf { !it.isDefault }
    }

    private fun resetSleep(ac: AcState, profiles: List<SleepProfile>): List<Command> =
        activeCustomProfile(ac, profiles)?.let { listOf(Command(it.activationFields(false), SleepProfile.KI)) }.orEmpty()

    private fun convertibleOff(ac: AcState): List<Command> =
        ac.spec("Convertible")?.let { listOf(Command.of(it.key, 0, it.ki)) }.orEmpty()

    private fun swingTo1(ac: AcState, vertical: Boolean = true, horizontal: Boolean = true): List<Command> = buildList {
        if (vertical) ac.spec("VerticalSwing")?.let { add(Command.of(it.key, 1, it.ki)) }
        if (horizontal) ac.spec("HorizontalSwing")?.let { add(Command.of(it.key, 1, it.ki)) }
    }

    fun power(ac: AcState, on: Boolean, profiles: List<SleepProfile>): List<Command> = buildList {
        add(Command.of(ac.key(Capability.Power), if (on) "on" else "off", Capability.Power.ki))
        if (on && ac.mode != "fan" && ac.temperature < 24.0) {
            add(Command.of(ac.key(Capability.Temperature), formatTemperature(24.0), Capability.Temperature.ki))
        }
        addAll(resetSleep(ac, profiles))
    }

    /** Models 102010/102011 ("Papagz"): JetStream, EcoNavi and mode changes exclude each other (`ACDevice.isPapagzAC`). */
    fun isPapagz(ac: AcState) = ac.device.modelId == "102010" || ac.device.modelId == "102011"

    private fun isOn(ac: AcState, name: String) =
        ac.spec(name)?.let { ac.statusOf(it)?.toString().equals("on", ignoreCase = true) } == true

    private fun off(ac: AcState, name: String): List<Command> =
        if (isOn(ac, name)) ac.spec(name)?.let { listOf(Command.of(it.key, "off", it.ki)) }.orEmpty() else emptyList()

    fun mode(ac: AcState, mode: String, profiles: List<SleepProfile>): List<Command> {
        val papagz = if (isPapagz(ac)) off(ac, "JetStream") + off(ac, "EcoNavi") else emptyList()
        return papagz + Command.of(ac.key(Capability.Mode), mode, Capability.Mode.ki) + resetSleep(ac, profiles)
    }

    /** A model toggle (`{key:"on|off"}`) plus the official side effects for ECO, Powerful, JetStream, Turbo. */
    fun toggle(ac: AcState, spec: CapabilitySpec, on: Boolean, profiles: List<SleepProfile>): List<Command> {
        val self = Command.of(spec.key, if (on) "on" else "off", spec.ki)
        return when (spec.name) {
            "ECOMode" -> if (on) {
                listOf(self, Command.of(ac.key(Capability.Temperature), formatTemperature(26.0), Capability.Temperature.ki)) + resetSleep(ac, profiles)
            } else {
                listOf(self)
            }
            "PowerfulMode" -> if (on) resetSleep(ac, profiles) + convertibleOff(ac) + self else listOf(self)
            "JetStream" -> if (on) {
                convertibleOff(ac) + self + swingTo1(ac) + resetSleep(ac, profiles) + (if (isPapagz(ac)) off(ac, "EcoNavi") else emptyList())
            } else {
                listOf(self)
            }
            "EcoNavi" -> if (on && isPapagz(ac)) listOf(self) + off(ac, "JetStream") else listOf(self)
            "TurboMode" -> if (on) listOf(self) + swingTo1(ac, horizontal = false) else listOf(self)
            else -> listOf(self)
        }
    }
}

// ---- notifications, firmware -----------------------------------------------------------------------

/** One `notificationmanager/log` entry. [tsMillis] from `ts` (seconds). */
data class AcNotification(
    val id: String,
    val severity: String,
    val tsMillis: Long,
    val title: String,
    val body: String,
    val deviceName: String?,
) {
    companion object {
        fun fromJson(o: JSONObject): AcNotification {
            val payload = o.optJSONObject("message")?.optJSONObject("payload") ?: JSONObject()
            fun p(k: String) = payload.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            val category = p("category")?.replace('-', ' ')?.lowercase()?.replaceFirstChar { it.uppercase() }
            val ts = o.optLong("ts", 0L).let { if (it in 1..9_999_999_999L) it * 1000 else it }
            return AcNotification(
                id = o.optString("notificationId").ifEmpty { o.optString("uniqueId") },
                severity = o.optString("severity"),
                tsMillis = ts,
                title = p("title") ?: category ?: "Notification",
                body = p("message") ?: p("ruleName") ?: listOfNotNull(p("capability"), p("status") ?: p("value")).joinToString(": "),
                deviceName = p("deviceName"),
            )
        }
    }
}

/** `fota/firmware/deviceId/{id}/download` response (`FotaInformationEntity`). */
data class FirmwareInfo(val latestVersion: String?, val isLatest: Boolean, val message: String?) {
    companion object {
        fun fromJson(o: JSONObject) = FirmwareInfo(
            latestVersion = o.optString("firmwareVersion").ifEmpty { null },
            isLatest = o.optBoolean("isLatestVersion", false),
            message = o.optString("message").ifEmpty { null },
        )
    }
}

/** A room ("space") in a MirAIe home. */
data class Space(val id: String, val name: String)
