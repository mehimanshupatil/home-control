package `in`.canvs.homecontrol.wiz

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class Bulb(
    val mac: String,
    val ip: String,
    val name: String,
    val on: Boolean = false,
    val dimming: Int = 100,
    val temp: Int? = null,
    val rgb: Triple<Int, Int, Int>? = null,
    val sceneId: Int = 0,
    /** Dynamic-scene speed (10–200), when the bulb reports it. */
    val speed: Int? = null,
    val rssi: Int? = null,
)

/** WiZ local UDP JSON-RPC on port 38899 (see PROTOCOL.md §2). */
class WizClient(context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Broadcasts `getPilot` and collects replies for [timeoutMs]. Returns bulbs keyed by MAC. */
    suspend fun discover(timeoutMs: Int = 2500): List<Pair<String, JSONObject>> = withContext(Dispatchers.IO) {
        val lock = wifi.createMulticastLock("wiz-discovery").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val msg = """{"method":"getPilot","params":{}}""".toByteArray()
                val targets = broadcastAddresses()
                val found = linkedMapOf<String, Pair<String, JSONObject>>()
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSend = 0L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() >= nextSend) {
                        targets.forEach { addr ->
                            runCatching { socket.send(DatagramPacket(msg, msg.size, addr, PORT)) }
                        }
                        nextSend = System.currentTimeMillis() + 800
                    }
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        val json = JSONObject(String(packet.data, 0, packet.length))
                        val result = json.optJSONObject("result") ?: continue
                        val mac = result.optString("mac").takeIf { it.isNotEmpty() } ?: continue
                        found[mac] = packet.address.hostAddress.orEmpty() to result
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
                found.values.toList()
            }
        } finally {
            lock.release()
        }
    }

    /** Sends `setPilot` to one bulb with up to 3 attempts; returns true on `success`. */
    suspend fun setPilot(ip: String, params: JSONObject): Boolean = withContext(Dispatchers.IO) {
        val msg = JSONObject().put("id", 1).put("method", "setPilot").put("params", params).toString().toByteArray()
        val addr = InetAddress.getByName(ip)
        DatagramSocket().use { socket ->
            socket.soTimeout = 700
            repeat(3) {
                runCatching {
                    socket.send(DatagramPacket(msg, msg.size, addr, PORT))
                    val buf = ByteArray(1024)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val reply = JSONObject(String(packet.data, 0, packet.length))
                    if (reply.optJSONObject("result")?.optBoolean("success") == true) return@withContext true
                }
            }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf(InetAddress.getByName("255.255.255.255"))
        val dhcp = wifi.dhcpInfo
        if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
            val bcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
            val bytes = ByteArray(4) { i -> (bcast shr (i * 8) and 0xFF).toByte() }
            result += InetAddress.getByAddress(bytes)
        }
        return result
    }

    companion object {
        const val PORT = 38899

        fun parseBulb(mac: String, ip: String, name: String, r: JSONObject): Bulb {
            val hasRgb = r.has("r") && r.has("g") && r.has("b")
            return Bulb(
                mac = mac,
                ip = ip,
                name = name,
                on = r.optBoolean("state"),
                dimming = r.optInt("dimming", 100),
                temp = if (r.has("temp")) r.optInt("temp") else null,
                rgb = if (hasRgb) Triple(r.optInt("r"), r.optInt("g"), r.optInt("b")) else null,
                sceneId = r.optInt("sceneId", 0),
                speed = if (r.has("speed")) r.optInt("speed") else null,
                rssi = if (r.has("rssi")) r.optInt("rssi") else null,
            )
        }

        val SCENES = listOf(
            1 to "Ocean", 2 to "Romance", 3 to "Sunset", 4 to "Party", 5 to "Fireplace",
            6 to "Cozy", 7 to "Forest", 8 to "Pastel colors", 9 to "Wake up", 10 to "Bedtime",
            11 to "Warm white", 12 to "Daylight", 13 to "Cool white", 14 to "Night light",
            15 to "Focus", 16 to "Relax", 17 to "True colors", 18 to "TV time", 19 to "Plant growth",
            20 to "Spring", 21 to "Summer", 22 to "Fall", 23 to "Deep dive", 24 to "Jungle",
            25 to "Mojito", 26 to "Club", 27 to "Christmas", 28 to "Halloween", 29 to "Candlelight",
            30 to "Golden white", 31 to "Pulse", 32 to "Steampunk",
        )

        /**
         * Scene menu groups. White: tunable-white presets. Colors: slow ambient colour scenes.
         * Moving: lively animated scenes (speed matters most here).
         */
        val SCENE_GROUPS: List<Pair<String, List<Int>>> = listOf(
            "White" to listOf(11, 12, 13, 30, 14, 15, 16, 17, 18, 19, 9, 10),
            "Colors" to listOf(1, 2, 3, 5, 6, 7, 8, 20, 21, 22, 23, 24, 25, 29),
            "Moving" to listOf(4, 26, 31, 32, 27, 28),
        )

        fun sceneName(id: Int): String? = SCENES.firstOrNull { it.first == id }?.second
    }
}
