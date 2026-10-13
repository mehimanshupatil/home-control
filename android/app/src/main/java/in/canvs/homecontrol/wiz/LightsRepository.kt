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
    /** Local rooms (this device only). */
    val rooms: List<LightRoom> = emptyList(),
    /** Saved light presets (this device only). */
    val presets: List<LightPreset> = emptyList(),
)

class LightsRepository(private val client: WizClient, private val store: SecureStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        LightsUiState(rooms = LightRoom.listFrom(store.wizRooms), presets = LightPreset.listFrom(store.wizPresets)),
    )
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

    /**
     * Identify: the WiZ app's `pulse` (`delta` -60, 800 ms). If the bulb doesn't confirm, falls back to
     * blinking with setPilot (toggle twice, ~400 ms apart), ending in its original state.
     */
    fun identify(mac: String) {
        val bulb = _state.value.bulbs.firstOrNull { it.mac == mac } ?: return
        scope.launch {
            if (demo || client.pulse(bulb.ip, delta = -60, durationMs = 800)) return@launch
            repeat(2) {
                client.setPilot(bulb.ip, JSONObject().put("state", !bulb.on))
                delay(400)
                client.setPilot(bulb.ip, JSONObject().put("state", bulb.on))
                delay(400)
            }
        }
    }

    // ---- rooms (local) ----------------------------------------------------------------------------

    fun createRoom(name: String): LightRoom {
        val room = LightRoom("room-${System.currentTimeMillis()}", name.trim(), emptyList())
        writeRooms(_state.value.rooms + room)
        return room
    }

    fun renameRoom(id: String, name: String) =
        writeRooms(_state.value.rooms.map { if (it.id == id) it.copy(name = name.trim()) else it })

    fun deleteRoom(id: String) = writeRooms(_state.value.rooms.filter { it.id != id })

    /** Puts [mac] in room [roomId] (null = no room); a bulb is in at most one room. */
    fun assignBulb(mac: String, roomId: String?) = writeRooms(
        _state.value.rooms.map { r ->
            val without = r.macs - mac
            if (r.id == roomId) r.copy(macs = without + mac) else r.copy(macs = without)
        },
    )

    private fun roomBulbs(roomId: String): List<Bulb> {
        val macs = _state.value.rooms.firstOrNull { it.id == roomId }?.macs.orEmpty()
        return _state.value.bulbs.filter { it.mac in macs }
    }

    fun setRoomOn(roomId: String, on: Boolean) = roomBulbs(roomId).forEach { setOn(it.mac, on) }

    fun setRoomBrightness(roomId: String, dimming: Int) = roomBulbs(roomId).forEach { setBrightness(it.mac, dimming) }

    fun setRoomScene(roomId: String, sceneId: Int) = roomBulbs(roomId).forEach { setScene(it.mac, sceneId) }

    private fun writeRooms(list: List<LightRoom>) {
        store.wizRooms = LightRoom.listJson(list)
        _state.update { it.copy(rooms = list) }
    }

    // ---- presets (local) --------------------------------------------------------------------------

    /** Saves the current state of [macs] (one bulb or a room) as a named preset. */
    fun savePreset(name: String, macs: List<String>) {
        val states = _state.value.bulbs.filter { it.mac in macs }.associate { it.mac to LightState.of(it) }
        if (states.isEmpty()) return
        val preset = LightPreset("preset-${System.currentTimeMillis()}", name.trim(), states)
        writePresets(_state.value.presets.filter { it.name != preset.name } + preset)
    }

    fun deletePreset(id: String) = writePresets(_state.value.presets.filter { it.id != id })

    /** Replays a preset to every saved bulb that is currently found on the network. */
    fun applyPreset(preset: LightPreset) {
        preset.states.forEach { (mac, st) ->
            send(mac, st.pilotParams()) { b -> b.copy(on = st.on, dimming = if (st.on) st.dimming else b.dimming, temp = st.temp, rgb = st.rgb, sceneId = st.sceneId, speed = st.speed) }
        }
    }

    private fun writePresets(list: List<LightPreset>) {
        store.wizPresets = LightPreset.listJson(list)
        _state.update { it.copy(presets = list) }
    }

    @Volatile
    private var demo = false

    private fun send(mac: String, params: JSONObject, optimistic: (Bulb) -> Bulb) {
        val bulb = _state.value.bulbs.firstOrNull { it.mac == mac } ?: return
        update(mac, optimistic)
        if (demo) return
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
        demo = true
        _state.value = LightsUiState(
            bulbs = listOf(
                Bulb("a2", "192.168.1.22", "Ceiling", on = true, dimming = 60, rgb = Triple(175, 82, 222), sceneId = 4, speed = 120),
                Bulb("a1", "192.168.1.21", "Desk Lamp", on = true, dimming = 80, temp = 2700),
                Bulb("a4", "192.168.1.24", "Bedside", on = true, dimming = 30, temp = 2200),
                Bulb("a3", "192.168.1.23", "Hallway"),
            ),
            rooms = listOf(
                LightRoom("room-1", "Living room", listOf("a2", "a1")),
                LightRoom("room-2", "Bedroom", listOf("a4")),
            ),
            presets = listOf(
                LightPreset("preset-1", "Movie", mapOf("a2" to LightState(true, 20, rgb = Triple(10, 132, 255)), "a1" to LightState(false, 80))),
                LightPreset("preset-2", "Reading", mapOf("a1" to LightState(true, 100, temp = 4200))),
            ),
        )
    }
}
