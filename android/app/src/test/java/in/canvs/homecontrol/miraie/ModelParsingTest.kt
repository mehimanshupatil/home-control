package `in`.canvs.homecontrol.miraie

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real model 130185 (from user diagnostics) and timer payload maths, PROTOCOL.md §1.8. */
class ModelParsingTest {

    private val model = """
        {"modelId":"130185","configCapabilities":[{"capability":"ProximityNotification"}],
         "controlCapabilities":[
         {"capability":"ACFanSpeed","attributes":[{"key":"acfs","values":["auto","quiet","low","medium","high"]}]},
         {"capability":"ACMode","attributes":[{"key":"acmd","values":["auto","cool","dry","fan"]}]},
         {"capability":"ACTemperature","attributes":[{"key":"actmp","range":{"increment":[{"unit":"celcius","value":1},{"unit":"fahrenheit","value":1}],"max":[{"unit":"celcius","value":30},{"unit":"fahrenheit","value":86}],"min":[{"unit":"celcius","value":16},{"unit":"fahrenheit","value":61}]}}]},
         {"capability":"ACTimer","attributes":[{"key":"actm","range":{"increment":[{"value":10}],"max":[{"value":1440}],"min":[{"value":10}]}}]},
         {"capability":"Convertible","attributes":[{"intValues":[110,100,90,80,70,55,40,0],"key":"cnv"}]},
         {"capability":"DisplayControl","attributes":[{"key":"acdc","values":["on","off"]}]}]}
    """.trimIndent()

    @Test
    fun coreFollowsModel() {
        val core = CapabilityCatalog.core(model)
        assertEquals(listOf("auto", "cool", "dry", "fan"), core.modes)
        assertEquals(listOf("auto", "quiet", "low", "medium", "high"), core.fanSpeeds)
        assertEquals(16.0, core.tempMin, 0.0)
        assertEquals(30.0, core.tempMax, 0.0) // celsius entry, not fahrenheit 86
        assertEquals(1.0, core.tempStep, 0.0)
        assertEquals(TimerSpec("actm", 10, 1440, 10), core.timer)
    }

    @Test
    fun coreDefaultsWithoutModel() {
        val core = CapabilityCatalog.core(null)
        assertEquals(listOf("auto", "cool", "heat", "dry", "fan"), core.modes)
        assertEquals(0.5, core.tempStep, 0.0)
        assertNull(core.timer)
    }

    @Test
    fun convertibleUsesIntValuesAscendingThenOff() {
        val spec = CapabilityCatalog.parse(model).first { it.name == "Convertible" }
        assertEquals("cnv", spec.key)
        assertEquals(listOf(40, 55, 70, 80, 90, 100, 110, 0), spec.intValues)
    }

    @Test
    fun displayUsesModelValues() {
        val spec = CapabilityCatalog.parse(model).first { it.name == "DisplayControl" }
        assertEquals(listOf("on", "off"), spec.values)
    }

    @Test
    fun timerOffSlotKeepsOnRemaining() {
        val now = 1_000_000L
        val status = JSONArray(listOf(-1L, now + 600)) // on timer due in 10 min
        assertEquals(listOf(3600L, 600L), TimerMath.payload(offSlot = true, seconds = 3600, status = status, nowSec = now))
    }

    @Test
    fun timerOnSlotKeepsOffRemaining() {
        val now = 1_000_000L
        assertEquals(listOf(1800L, 7200L), TimerMath.payload(false, 7200, listOf(now + 1800, -1), now))
    }

    @Test
    fun timerCancelAndExpiredSlots() {
        val now = 1_000_000L
        // Cancel off; on already expired → both unset.
        assertEquals(listOf(-1L, -1L), TimerMath.payload(true, -1, "[${now + 900}, ${now - 5}]", now))
        // Unknown status → other slot unset.
        assertEquals(listOf(-1L, 600L), TimerMath.payload(false, 600, null, now))
    }

    @Test
    fun timerUtcForOptimisticStatus() {
        assertEquals(listOf(1_003_600L, -1L), TimerMath.toUtc(listOf(3600L, -1L), 1_000_000L))
    }

    @Test
    fun temperatureMatchesOfficialFormat() {
        assertEquals("26.0", formatTemperature(26.0))
        assertEquals("24.5", formatTemperature(24.5))
    }
}
