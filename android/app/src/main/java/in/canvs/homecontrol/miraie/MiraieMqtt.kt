package `in`.canvs.homecontrol.miraie

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.security.MessageDigest
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * One MQTT connection per MirAIe home: username = homeId, password = access token.
 * Blocking calls; invoke from Dispatchers.IO.
 */
class MiraieMqtt(
    private val homeId: String,
    userId: String,
    private val statusTopics: List<String>,
    private val onStatus: (topic: String, payload: JSONObject) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
) {
    private val client = MqttClient(BROKER, clientId(userId, homeId), MemoryPersistence())

    init {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                subscribeAll()
                onConnectionChange(true)
            }

            override fun connectionLost(cause: Throwable?) = onConnectionChange(false)

            override fun messageArrived(topic: String, message: MqttMessage) {
                val json = runCatching { JSONObject(String(message.payload)) }.getOrNull() ?: return
                onStatus(topic.removeSuffix("/status"), json)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
    }

    val isConnected: Boolean get() = client.isConnected

    fun connect(accessToken: String) {
        if (client.isConnected) client.disconnect()
        val options = MqttConnectOptions().apply {
            userName = homeId
            password = accessToken.toCharArray()
            isCleanSession = true
            keepAliveInterval = 30
            isAutomaticReconnect = false
            connectionTimeout = 20
            socketFactory = SSLSocketFactory.getDefault()
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
        }
        client.connect(options)
    }

    private fun subscribeAll() {
        statusTopics.forEach { runCatching { client.subscribe("$it/status", 0) } }
    }

    /** Publishes one control field to `<topic>/control` using the app's envelope (`ki`, `cnt`, `sid`). */
    fun publishControl(topic: String, key: String, value: Any, ki: Int) {
        val payload = JSONObject()
            .put(key, value)
            .put("ki", ki)
            .put("cnt", "an")
            .put("sid", "1")
        client.publish("$topic/control", MqttMessage(payload.toString().toByteArray()).apply {
            qos = 0
            isRetained = false
        })
    }

    fun close() {
        runCatching { if (client.isConnected) client.disconnect() }
        runCatching { client.close() }
    }

    private companion object {
        const val BROKER = "ssl://mqtt.miraie.in:8883"

        fun clientId(userId: String, homeId: String): String {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest("$userId##$homeId".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            return "AN" + hash + Random.nextInt(10000, 99999)
        }
    }
}
