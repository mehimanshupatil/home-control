package `in`.canvs.homecontrol.miraie

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    private val key = "00112233445566778899aabbccddeeff"
    private val iv = "0f0e0d0c0b0a09080706050403020100"
    private val token = "eyJhbGciOiJIUzI1NiJ9.secret-access-token"

    private val model = """
        {"modelId":"m1","controlCapabilities":[
         {"capability":"VerticalSwing","attributes":[{"key":"acvs","range":{"min":[{"value":0}],"max":[{"value":5}],"increment":[{"value":1}]}}]},
         {"capability":"DisplayControl","attributes":[{"key":"acdc"}]},
         {"capability":"ECOMode","attributes":[{"key":"acem","values":["on","off"]}]},
         {"capability":"ACGenMode","attributes":[{"key":"acgm","values":["60","100"]}]}],
         "statusCapabilitiesV2":[{"capability":"RoomHumidity","attributes":[{"key":"rmhmd"}]}],
         "accessToken":"$token"}
    """.trimIndent()

    private fun device() = AcState(
        device = MiraieDevice("d1", "Bedroom AC", "m1", "AC", "h/d1", "h", "Bedroom"),
        localKeys = LocalKeys("AA:BB:CC:DD:EE:FF", "192.168.1.50", key, iv),
        rawStatus = mapOf("ps" to "on", "rmhmd" to 61, "refreshToken" to token, "password" to "hunter2"),
    ).withModel(model)

    @Test
    fun diagnosticsNeverContainSecrets() {
        val json = Diagnostics.build("1.0", listOf(device()), generatedAtMillis = 0)
        assertFalse("full key leaked", json.contains(key))
        assertFalse("full IV leaked", json.contains(iv))
        assertFalse("token leaked", json.contains(token))
        assertFalse("password leaked", json.contains("hunter2"))
        assertTrue("masked key present", json.contains("0011..."))
        assertTrue("status kept", json.contains("rmhmd"))
    }

    @Test
    fun parsesCapabilitiesFromModelAttributes() {
        val caps = device().capabilities.associateBy { it.name }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), caps.getValue("VerticalSwing").intValues)
        assertEquals("acvs", caps.getValue("VerticalSwing").key)
        assertEquals(5, caps.getValue("VerticalSwing").ki)
        assertEquals(listOf("on", "night", "off"), caps.getValue("DisplayControl").values)
        assertEquals(CapKind.Toggle, caps.getValue("ECOMode").kind)
        assertEquals(listOf(60, 100, 0), caps.getValue("ACGenMode").intValues)
        assertEquals(4, CapabilityCatalog.payload(caps.getValue("ACGenMode"), 60))
        assertEquals(CapKind.Status, caps.getValue("RoomHumidity").kind)
        assertEquals("Auto", CapabilityCatalog.optionLabel(caps.getValue("VerticalSwing"), 0))
    }
}
