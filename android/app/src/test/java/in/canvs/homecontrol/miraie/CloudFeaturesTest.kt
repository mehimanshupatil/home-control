package `in`.canvs.homecontrol.miraie

import `in`.canvs.homecontrol.wiz.LightPreset
import `in`.canvs.homecontrol.wiz.LightRoom
import `in`.canvs.homecontrol.wiz.LightState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Payloads for the cloud features, checked against the decompiled classes cited in CloudFeatures.kt. */
class CloudFeaturesTest {

    // ---- sleep profiles --------------------------------------------------------------------------

    @Test
    fun sleepTemperaturesFormatLikeSleepProfileEntity() {
        val p = SleepProfile(null, "Cool night", listOf(24.0, 25.5, 9.0), offAtEnd = true)
        // "%04.1f" pads to width 4 and the trailing "00.0" means switch off at the end.
        assertEquals(listOf("24.0", "25.5", "09.0", "00.0"), p.temperatureStrings())
        assertEquals(listOf("24.0", "25.5", "09.0"), p.copy(offAtEnd = false).temperatureStrings())
    }

    @Test
    fun sleepBodies() {
        val p = SleepProfile(null, "Cool night", listOf(24.0, 25.0), offAtEnd = true)
        val create = p.createBody()
        assertFalse(create.has("profileId"))
        assertEquals("Cool night", create.getString("profileName"))
        assertEquals(JSONArray(listOf("24.0", "25.0", "00.0")).toString(), create.getJSONArray("temperatures").toString())
        val update = p.copy(id = "p1").updateBody()
        assertEquals(setOf("profileName", "temperatures"), update.keys().asSequence().toSet())
    }

    @Test
    fun sleepActivationMatchesACProfileCapability() {
        val custom = SleepProfile("p1", "Cool night", listOf(24.0, 25.0), offAtEnd = false)
        val on = custom.activationFields(true)
        assertEquals("on", on.getString("acsp"))
        assertEquals("p1", on.getString("pid"))
        assertEquals(JSONArray(listOf("24.0", "25.0")).toString(), on.getJSONArray("temps").toString())
        assertEquals("off", custom.activationFields(false).getString("acsp"))
        // The built-in profile is sent as pid "default", whatever its server id.
        val def = SleepProfile("srv-1", "default", listOf(26.0), offAtEnd = false)
        assertEquals("default", def.activationFields(true).getString("pid"))
        val rule = JSONObject(custom.rulePayload(12))
        assertEquals(setOf("acsp", "ki", "pid"), rule.keys().asSequence().toSet())
        assertEquals("on", rule.getString("acsp"))
        assertEquals(12, rule.getInt("ki"))
        assertEquals("p1", rule.getString("pid"))
    }

    @Test
    fun sleepParsingAndActiveState() {
        val p = SleepProfile.fromJson(JSONObject("""{"profileId":"p1","profileName":"Night","smartRuleId":["r1"],"temperatures":["24.0","25.0","00.0"]}"""))
        assertEquals(listOf(24.0, 25.0), p.temps)
        assertTrue(p.offAtEnd)
        assertEquals(listOf("r1"), p.ruleIds)
        assertTrue(p.isActive("p1"))
        assertFalse(p.isActive("none"))
        assertFalse(p.isActive(""))
        assertTrue(SleepProfile("x", "default", emptyList(), false).isActive("default"))
    }

    // ---- rules ---------------------------------------------------------------------------------------

    @Test
    fun ruleShapeMatchesRulePayload() {
        val p = SleepProfile("p1", "Cool night", listOf(24.0), offAtEnd = false)
        val body = RulePayloads.sleepRule("h1", "u1", "Sleep profile: Cool night", "d1", p, 12, Schedule(22, 30, setOf(2, 3, 4, 5, 6)))
        assertEquals("ENABLE", body.getString("ruleStatus"))
        assertEquals("User", body.getString("ruleType"))
        assertEquals("v2", body.getString("schemaVersion"))
        assertEquals("u1", body.getString("userId"))
        assertEquals("u1", body.getString("primaryUserId"))
        assertFalse(body.has("ruleId"))
        val trigger = body.getJSONArray("triggers").getJSONArray(0).getJSONObject(0)
        assertEquals("Timer", trigger.getString("type"))
        assertEquals("EQ", trigger.getString("check"))
        assertEquals("Time", trigger.getString("property"))
        assertEquals(2230, trigger.getInt("value"))
        assertEquals("M,T,W,TH,F", trigger.getString("repeatType"))
        val action = body.getJSONObject("rule").getJSONArray("actions").getJSONObject(0)
        assertEquals("d1", action.getString("deviceId"))
        assertEquals("p1", action.getString("profileId"))
        assertEquals("0.1", action.getString("payloadVersion"))
        assertEquals("on", JSONObject(action.getString("payload")).getString("acsp"))
        assertTrue(body.getJSONObject("rule").getBoolean("sendPushNotification"))
        assertEquals("r9", RulePayloads.sleepRule("h1", "u1", "n", "d1", p, 12, Schedule(7, 0, emptySet()), "r9").getString("ruleId"))
    }

    @Test
    fun scheduleRepeatTypes() {
        assertEquals("daily", Schedule(0, 0, (1..7).toSet()).repeatType)
        assertEquals("once", Schedule(0, 0, emptySet()).repeatType)
        assertEquals("S,SA", Schedule(0, 0, setOf(7, 1)).repeatType)
        val parsed = Schedule.parse(705, "S,TH")
        assertEquals(7, parsed.hour)
        assertEquals(5, parsed.minute)
        assertEquals(setOf(1, 5), parsed.days)
    }

    @Test
    fun pauseBody() {
        val b = RulePayloads.pause("r1", 1_700_000_000_000L).getJSONObject(0)
        assertEquals("pause", b.getString("action"))
        assertEquals("1700000000000", b.getString("startTime"))
        assertFalse(b.has("endTime"))
    }

    // ---- energy -------------------------------------------------------------------------------------

    @Test
    fun energyWindows() {
        val today = LocalDate.of(2026, 10, 13)
        val daily = EnergyMath.window(EnergyGrain.Daily, today)
        assertEquals("07102026", daily.startDate)
        assertEquals("13102026", daily.endDate)
        assertEquals(7, daily.buckets.size)
        val weekly = EnergyMath.window(EnergyGrain.Weekly, today)
        assertEquals("26082026", weekly.startDate) // 6 weeks before the last week's start (7 Oct)
        assertEquals("07102026", weekly.endDate)
        assertEquals(7, weekly.buckets.size)
        val monthly = EnergyMath.window(EnergyGrain.Monthly, today)
        assertEquals("042026", monthly.startDate) // this month and the 6 before it (DayHelper.setupMonthData)
        assertEquals("102026", monthly.endDate)
        assertEquals(7, monthly.buckets.size)
        assertEquals("Daily", EnergyGrain.Daily.api)
    }

    @Test
    fun energyZeroFill() {
        val w = EnergyMath.window(EnergyGrain.Daily, LocalDate.of(2026, 10, 13))
        val filled = EnergyMath.fill(w, JSONArray("""[{"day":"13102026","power":"2.5"},{"day":"09102026","power":"1.25"},{"day":"01012020","power":"9"}]"""))
        assertEquals(2.5, filled.buckets.last().kwh, 0.0)
        assertEquals(1.25, filled.buckets[2].kwh, 0.0)
        assertEquals(0.0, filled.buckets[0].kwh, 0.0)
        assertEquals(3.75, filled.buckets.sumOf { it.kwh }, 1e-9)
    }

    // ---- side effects -----------------------------------------------------------------------------------

    private val model = """
        {"controlCapabilities":[
         {"capability":"ECOMode","attributes":[{"key":"acem"}]},
         {"capability":"PowerfulMode","attributes":[{"key":"acpm"}]},
         {"capability":"JetStream","attributes":[{"key":"acjs"}]},
         {"capability":"Convertible","attributes":[{"intValues":[110,100,0],"key":"cnv"}]},
         {"capability":"VerticalSwing","attributes":[{"key":"acvs"}]},
         {"capability":"HorizontalSwing","attributes":[{"key":"achs"}]},
         {"capability":"SleepProfile","attributes":[{"key":"acsp"}]}]}
    """.trimIndent()

    private fun ac(power: Boolean = false, mode: String = "cool", temp: Double = 22.0, acsp: String = "none") =
        AcState(MiraieDevice("d1", "AC", "m", "AC", "h/d1", "h"), power = power, mode = mode, temperature = temp, rawStatus = mapOf("acsp" to acsp))
            .withModel(model)

    private val custom = SleepProfile("p1", "Cool night", listOf(24.0), offAtEnd = false)

    private fun keys(cmds: List<Command>) = cmds.map { c -> c.key + "=" + c.fields.get(c.key) }

    @Test
    fun powerOnRaisesTemperatureAndStopsCustomProfile() {
        val cmds = SideEffects.power(ac(temp = 20.0, acsp = "p1"), on = true, profiles = listOf(custom))
        assertEquals(listOf("ps=on", "actmp=24.0", "acsp=off"), keys(cmds))
        assertEquals(1, cmds[0].ki)
        assertEquals(2, cmds[1].ki)
        assertEquals(SleepProfile.KI, cmds[2].ki)
        // Fan mode or already warm: no temperature change; no active profile: nothing else.
        assertEquals(listOf("ps=on"), keys(SideEffects.power(ac(mode = "fan", temp = 20.0), true, emptyList())))
        assertEquals(listOf("ps=on"), keys(SideEffects.power(ac(temp = 25.0), true, emptyList())))
        // The default profile is never stopped automatically.
        assertEquals(listOf("ps=off"), keys(SideEffects.power(ac(acsp = "default"), false, listOf(SleepProfile("d", "default", emptyList(), false)))))
    }

    @Test
    fun toggleSideEffects() {
        val a = ac(power = true, acsp = "p1")
        fun spec(name: String) = a.spec(name)!!
        assertEquals(listOf("acem=on", "actmp=26.0", "acsp=off"), keys(SideEffects.toggle(a, spec("ECOMode"), true, listOf(custom))))
        assertEquals(listOf("acem=off"), keys(SideEffects.toggle(a, spec("ECOMode"), false, listOf(custom))))
        assertEquals(listOf("acsp=off", "cnv=0", "acpm=on"), keys(SideEffects.toggle(a, spec("PowerfulMode"), true, listOf(custom))))
        assertEquals(listOf("cnv=0", "acjs=on", "acvs=1", "achs=1", "acsp=off"), keys(SideEffects.toggle(a, spec("JetStream"), true, listOf(custom))))
        assertEquals(listOf("acmd=dry", "acsp=off"), keys(SideEffects.mode(a, "dry", listOf(custom))))
    }

    @Test
    fun papagzExclusivity() {
        val papagzModel = """
            {"controlCapabilities":[
             {"capability":"JetStream","attributes":[{"key":"acjs"}]},
             {"capability":"EcoNavi","attributes":[{"key":"acen"}]}]}
        """.trimIndent()
        fun papagz(id: String, js: String, en: String) =
            AcState(MiraieDevice("d1", "AC", id, "AC", "h/d1", "h"), power = true, rawStatus = mapOf("acjs" to js, "acen" to en)).withModel(papagzModel)
        val a = papagz("102010", js = "on", en = "on")
        assertEquals(listOf("acjs=off", "acen=off", "acmd=dry"), keys(SideEffects.mode(a, "dry", emptyList())))
        val b = papagz("102011", js = "off", en = "on")
        assertEquals(listOf("acjs=on", "acen=off"), keys(SideEffects.toggle(b, b.spec("JetStream")!!, true, emptyList())))
        val c = papagz("102010", js = "on", en = "off")
        assertEquals(listOf("acen=on", "acjs=off"), keys(SideEffects.toggle(c, c.spec("EcoNavi")!!, true, emptyList())))
        // Other models: no exclusivity.
        val d = papagz("130185", js = "on", en = "on")
        assertEquals(listOf("acmd=dry"), keys(SideEffects.mode(d, "dry", emptyList())))
    }

    @Test
    fun ruleNames() {
        assertEquals("Schedule Cool night", RulePayloads.ruleName(custom))
        assertEquals("Regular", RulePayloads.ruleName(SleepProfile("d", "default", emptyList(), false)))
    }

    // ---- misc ---------------------------------------------------------------------------------------------

    @Test
    fun airQualityBands() {
        assertEquals(AirQuality.Band.Excellent, AirQuality.band(30))
        assertEquals(AirQuality.Band.Good, AirQuality.band(31))
        assertEquals(AirQuality.Band.Unhealthy, AirQuality.band(250))
        assertEquals(AirQuality.Band.Hazardous, AirQuality.band(251))
        assertNull(AirQuality.band(-1))
    }

    @Test
    fun filterHealthLevels() {
        assertEquals(FilterHealth.Level.Good, FilterHealth(99, 100, 200, 500).level)
        assertEquals(FilterHealth.Level.Reminder, FilterHealth(100, 100, 200, 500).level)
        assertEquals(FilterHealth.Level.Warning, FilterHealth(230, 100, 200, 500).level)
        assertEquals(FilterHealth.Level.Alert, FilterHealth(500, 100, 200, 500).level)
    }

    @Test
    fun nanoeGaHasNoKi() {
        val spec = CapabilityCatalog.parse("""{"controlCapabilities":[{"capability":"NanoeGA","attributes":[{"key":"acnga"}]}]}""").single()
        assertEquals(CapKind.Cycle, spec.kind)
        assertEquals(listOf("off", "on", "auto"), spec.values)
        assertEquals(CapabilityCatalog.NO_KI, spec.ki)
    }

    @Test
    fun notificationParsing() {
        val n = AcNotification.fromJson(
            JSONObject("""{"notificationId":"n1","severity":"alert","ts":1700000000,"message":{"payload":{"title":"Clean filter","message":"Run 230 h","deviceName":"AC"}}}"""),
        )
        assertEquals(1_700_000_000_000L, n.tsMillis)
        assertEquals("Clean filter", n.title)
        assertEquals("Run 230 h", n.body)
    }

    @Test
    fun lightPresetsRoundTrip() {
        val p = LightPreset("x", "Movie", mapOf("a" to LightState(true, 20, rgb = Triple(1, 2, 3)), "b" to LightState(false, 80)))
        val back = LightPreset.listFrom(LightPreset.listJson(listOf(p))).single()
        assertEquals(p, back)
        assertEquals("""{"state":false}""", back.states.getValue("b").pilotParams().toString())
        val on = back.states.getValue("a").pilotParams()
        assertEquals(3, on.getInt("b"))
        assertEquals(20, on.getInt("dimming"))
        val rooms = listOf(LightRoom("r", "Bedroom", listOf("a")))
        assertEquals(rooms, LightRoom.listFrom(LightRoom.listJson(rooms)))
    }
}
