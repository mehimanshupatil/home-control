package `in`.canvs.homecontrol

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Quick Settings tile that toggles power on the first MirAIe AC. */
class AcTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        s.launch {
            if (app.ac.state.value.devices.isEmpty()) launch(Dispatchers.IO) { app.ac.refresh() }
            app.ac.state.collectLatest { render() }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        val ac = app.ac.state.value.devices.firstOrNull() ?: return
        app.ac.setPower(ac.device.deviceId, !ac.power)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val st = app.ac.state.value
        val ac = st.devices.firstOrNull()
        when {
            !st.loggedIn -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "AC (sign in)"
            }
            ac == null -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "AC"
            }
            else -> {
                tile.state = if (ac.power) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                tile.label = ac.device.name
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    tile.subtitle = if (ac.power) "${fmt(ac.temperature)}° ${ac.mode}" else "Off"
                }
            }
        }
        tile.updateTile()
    }

    private fun fmt(t: Double) = if (t % 1.0 == 0.0) t.toInt().toString() else t.toString()
}
