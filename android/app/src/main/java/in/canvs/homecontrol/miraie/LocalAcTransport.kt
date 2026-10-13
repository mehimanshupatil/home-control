package `in`.canvs.homecontrol.miraie

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MirAIe LAN control over UDP port 5001 (PROTOCOL.md §1.6).
 * Discovery request is plaintext; replies, commands and status are AES-CBC/NoPadding with zero padding.
 */
class LocalAcTransport(
    context: Context,
    private val onStatus: (deviceId: String, payload: JSONObject) -> Unit,
) {
    private class Entry(val deviceId: String, val keys: LocalKeys, @Volatile var ip: String?)

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, Entry>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var receiveJob: Job? = null

    /** Binds port 5001 and starts the receive loop. Throws if the port can't be bound. */
    @Synchronized
    fun start() {
        if (socket != null) return
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
            bind(InetSocketAddress(PORT))
        }
        socket = s
        lock = wifi.createMulticastLock("miraie-local").apply { setReferenceCounted(false); acquire() }
        receiveJob = scope.launch { receiveLoop(s) }
    }

    @Synchronized
    fun stop() {
        receiveJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        runCatching { lock?.release() }
        lock = null
    }

    fun register(deviceId: String, keys: LocalKeys) {
        entries[deviceId] = Entry(deviceId, keys, keys.ip)
    }

    fun ipOf(deviceId: String): String? = entries[deviceId]?.ip

    /**
     * Sends `DISC_REQ_<MAC>` to the stored IP and the subnet broadcast address until the AC answers
     * or [timeoutMs] passes. Returns the AC's IP on success.
     */
    suspend fun discover(deviceId: String, timeoutMs: Long = 3000): String? {
        val entry = entries[deviceId] ?: return null
        val s = socket ?: return null
        val macId = normalizeMac(entry.keys.mac)
        val waiter = CompletableDeferred<String>()
        pending[macId] = waiter
        return try {
            withTimeoutOrNull(timeoutMs) {
                val sender = launch(Dispatchers.IO) {
                    val msg = "DISC_REQ_$macId".toByteArray()
                    while (isActive) {
                        val targets = buildList {
                            entry.ip?.let { add(InetAddress.getByName(it)) }
                            addAll(broadcastAddresses())
                        }
                        targets.forEach { runCatching { s.send(DatagramPacket(msg, msg.size, it, PORT)) } }
                        delay(700)
                    }
                }
                waiter.await().also { sender.cancel() }
            }?.also { entry.ip = it }
        } finally {
            pending.remove(macId)
        }
    }

    /** Encrypts `{key: value, ki: 1, cnt: "an", sid: "1"}` and sends it to the AC. */
    suspend fun send(deviceId: String, key: String, value: Any) = withContext(Dispatchers.IO) {
        val entry = entries[deviceId] ?: error("No local keys for device")
        val ip = entry.ip ?: error("AC not found on LAN")
        val s = socket ?: error("Local transport not started")
        val json = JSONObject().put(key, value).put("ki", 1).put("cnt", "an").put("sid", "1").toString()
        val data = crypt(Cipher.ENCRYPT_MODE, entry.keys, zeroPad(json.toByteArray()))
        s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), PORT))
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        while (!s.isClosed && receiveJob?.isActive != false) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val bytes = packet.data.copyOf(packet.length)
            val from = packet.address.hostAddress ?: continue
            if (bytes.size % 16 != 0) continue // our own plaintext broadcast echo, or junk
            // Try the entry already bound to this IP first, then every known key.
            val candidates = entries.values.sortedByDescending { it.ip == from }
            for (entry in candidates) {
                val text = runCatching {
                    String(crypt(Cipher.DECRYPT_MODE, entry.keys, bytes)).trimEnd('\u0000')
                }.getOrNull() ?: continue
                if (text.startsWith("DISC_RSP_", ignoreCase = true)) {
                    val mac = normalizeMac(text.substring(9))
                    if (mac == normalizeMac(entry.keys.mac)) {
                        entry.ip = from
                        pending[mac]?.complete(from)
                        break
                    }
                } else if (text.startsWith("{")) {
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    entry.ip = from
                    onStatus(entry.deviceId, json)
                    break
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val dhcp = wifi.dhcpInfo
        if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
            val bcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
            result += InetAddress.getByAddress(ByteArray(4) { i -> (bcast shr (i * 8) and 0xFF).toByte() })
        }
        result += InetAddress.getByName("255.255.255.255")
        return result
    }

    companion object {
        const val PORT = 5001

        fun normalizeMac(mac: String): String = mac.filter { it.isLetterOrDigit() }.uppercase()

        fun zeroPad(data: ByteArray): ByteArray {
            val rem = data.size % 16
            return if (rem == 0) data else data.copyOf(data.size + 16 - rem)
        }

        fun crypt(mode: Int, keys: LocalKeys, data: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(mode, SecretKeySpec(hex(keys.keyHex), "AES"), IvParameterSpec(hex(keys.ivHex)))
            return cipher.doFinal(data)
        }

        private fun hex(s: String): ByteArray {
            val clean = s.trim()
            return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}
