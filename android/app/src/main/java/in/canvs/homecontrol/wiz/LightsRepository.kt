package `in`.canvs.homecontrol.wiz

import `in`.canvs.homecontrol.data.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

data class LightsUiState(
    val scanning: Boolean = false,
    val bulbs: List<Bulb> = emptyList(),
    val error: String? = null,
)

class LightsRepository(private val client: WizClient, private val store: SecureStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LightsUiState())
    val state: StateFlow<LightsUiState> = _state.asStateFlow()

    suspend fun discover() {
        _state.update { it.copy(scanning = true, error = null) }
        val found = runCatching { client.discover() }
        found.onSuccess { list ->
            val bulbs = list.mapIndexed { i, (ip, result) ->
                val mac = result.optString("mac")
                WizClient.parseBulb(mac, ip, store.bulbName(mac) ?: "Bulb ${i + 1} (${mac.takeLast(4)})", result)
            }.sortedBy { it.name.lowercase() }
            _state.update {
                it.copy(
                    scanning = false,
                    bulbs = bulbs,
                    error = if (bulbs.isEmpty()) "No WiZ bulbs found on this Wi-Fi network" else null,
                )
            }
        }.onFailure { e ->
            _state.update { it.copy(scanning = false, error = e.message ?: "Discovery failed") }
        }
    }

    fun rename(mac: String, name: String) {
        store.setBulbName(mac, name)
        update(mac) { it.copy(name = name) }
    }

    fun setOn(mac: String, on: Boolean) =
        send(mac, JSONObject().put("state", on)) { it.copy(on = on) }

    fun setBrightness(mac: String, dimming: Int) {
        val d = dimming.coerceIn(10, 100)
        send(mac, JSONObject().put("state", true).put("dimming", d)) { it.copy(on = true, dimming = d) }
    }

    fun setTemperature(mac: String, kelvin: Int) {
        val k = kelvin.coerceIn(2200, 6500)
        send(mac, JSONObject().put("state", true).put("temp", k)) {
            it.copy(on = true, temp = k, rgb = null, sceneId = 0)
        }
    }

    fun setColor(mac: String, r: Int, g: Int, b: Int) =
        send(mac, JSONObject().put("state", true).put("r", r).put("g", g).put("b", b)) {
            it.copy(on = true, rgb = Triple(r, g, b), temp = null, sceneId = 0)
        }

    fun setScene(mac: String, sceneId: Int) =
        send(mac, JSONObject().put("state", true).put("sceneId", sceneId).put("speed", 100)) {
            it.copy(on = true, sceneId = sceneId, speed = 100)
        }

    fun setAll(on: Boolean) = _state.value.bulbs.forEach { setOn(it.mac, on) }

    /** Speed for the active (dynamic) scene, 10–200. */
    fun setSpeed(mac: String, speed: Int) {
        val bulb = _state.value.bulbs.firstOrNull { it.mac == mac } ?: return
        if (bulb.sceneId <= 0) return
        val v = speed.coerceIn(10, 200)
        send(mac, JSONObject().put("state", true).put("sceneId", bulb.sceneId).put("speed", v)) { it.copy(speed = v) }
    }

    /** Blinks the bulb (toggle twice, ~400 ms apart) and ends in its original state. Uses setPilot only. */
    fun identify(mac: String) {
        val bulb = _state.value.bulbs.firstOrNull { it.mac == mac } ?: return
        scope.launch {
            repeat(2) {
                client.setPilot(bulb.ip, JSONObject().put("state", !bulb.on))
                delay(400)
                client.setPilot(bulb.ip, JSONObject().put("state", bulb.on))
                delay(400)
            }
        }
    }

    private fun send(mac: String, params: JSONObject, optimistic: (Bulb) -> Bulb) {
        val bulb = _state.value.bulbs.firstOrNull { it.mac == mac } ?: return
        update(mac, optimistic)
        scope.launch {
            if (!client.setPilot(bulb.ip, params)) {
                _state.update { it.copy(error = "${bulb.name} did not respond") }
            }
        }
    }

    private fun update(mac: String, transform: (Bulb) -> Bulb) {
        _state.update { s -> s.copy(bulbs = s.bulbs.map { if (it.mac == mac) transform(it) else it }) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Debug builds only: sample bulbs for UI screenshots. */
    fun seedDemo() {
        _state.value = LightsUiState(
            bulbs = listOf(
                Bulb("a2", "192.168.1.22", "Ceiling", on = true, dimming = 60, rgb = Triple(175, 82, 222), sceneId = 4, speed = 120),
                Bulb("a1", "192.168.1.21", "Desk Lamp", on = true, dimming = 80, temp = 2700),
                Bulb("a3", "192.168.1.23", "Hallway"),
            ),
        )
    }
}
