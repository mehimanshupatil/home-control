package `in`.canvs.homecontrol.wiz

import org.json.JSONArray
import org.json.JSONObject

/** A local room: a named set of bulbs (by MAC). A bulb belongs to at most one room. Stored on this device only. */
data class LightRoom(val id: String, val name: String, val macs: List<String>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("macs", JSONArray(macs))

    companion object {
        fun fromJson(o: JSONObject): LightRoom {
            val arr = o.optJSONArray("macs") ?: JSONArray()
            return LightRoom(o.optString("id"), o.optString("name"), (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() })
        }

        fun listFrom(json: String?): List<LightRoom> = runCatching {
            val arr = JSONArray(json ?: return emptyList())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::fromJson).filter { it.id.isNotEmpty() }
        }.getOrDefault(emptyList())

        fun listJson(list: List<LightRoom>): String = JSONArray(list.map { it.toJson() }).toString()
    }
}

/** One bulb's saved light: exactly one of scene, colour or white temperature (or just on/off + brightness). */
data class LightState(
    val on: Boolean,
    val dimming: Int,
    val temp: Int? = null,
    val rgb: Triple<Int, Int, Int>? = null,
    val sceneId: Int = 0,
    val speed: Int? = null,
) {
    /** `setPilot` params that reproduce this state. */
    fun pilotParams(): JSONObject {
        val p = JSONObject().put("state", on)
        if (!on) return p
        p.put("dimming", dimming.coerceIn(10, 100))
        when {
            sceneId > 0 -> { p.put("sceneId", sceneId); speed?.let { p.put("speed", it.coerceIn(10, 200)) } }
            rgb != null -> p.put("r", rgb.first).put("g", rgb.second).put("b", rgb.third)
            temp != null -> p.put("temp", temp.coerceIn(2200, 6500))
        }
        return p
    }

    fun toJson(): JSONObject = JSONObject().put("on", on).put("dimming", dimming).apply {
        temp?.let { put("temp", it) }
        rgb?.let { put("rgb", JSONArray(listOf(it.first, it.second, it.third))) }
        if (sceneId > 0) put("sceneId", sceneId)
        speed?.let { put("speed", it) }
    }

    companion object {
        fun of(b: Bulb) = LightState(b.on, b.dimming, b.temp, b.rgb, b.sceneId, b.speed)

        fun fromJson(o: JSONObject): LightState {
            val rgb = o.optJSONArray("rgb")?.takeIf { it.length() == 3 }?.let { Triple(it.optInt(0), it.optInt(1), it.optInt(2)) }
            return LightState(
                on = o.optBoolean("on", true),
                dimming = o.optInt("dimming", 100),
                temp = if (o.has("temp")) o.optInt("temp") else null,
                rgb = rgb,
                sceneId = o.optInt("sceneId", 0),
                speed = if (o.has("speed")) o.optInt("speed") else null,
            )
        }
    }
}

/** A saved preset: per-bulb states captured from one bulb or a room, replayed with `setPilot`. */
data class LightPreset(val id: String, val name: String, val states: Map<String, LightState>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("states", JSONObject().apply { states.forEach { (mac, st) -> put(mac, st.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): LightPreset {
            val st = o.optJSONObject("states") ?: JSONObject()
            return LightPreset(o.optString("id"), o.optString("name"), st.keys().asSequence().associateWith { LightState.fromJson(st.getJSONObject(it)) })
        }

        fun listFrom(json: String?): List<LightPreset> = runCatching {
            val arr = JSONArray(json ?: return emptyList())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::fromJson).filter { it.id.isNotEmpty() }
        }.getOrDefault(emptyList())

        fun listJson(list: List<LightPreset>): String = JSONArray(list.map { it.toJson() }).toString()
    }
}
