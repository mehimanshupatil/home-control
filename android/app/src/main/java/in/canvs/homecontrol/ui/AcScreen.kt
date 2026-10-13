package `in`.canvs.homecontrol.ui

import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.stateDescription
import `in`.canvs.homecontrol.miraie.CapKind
import `in`.canvs.homecontrol.miraie.CapabilityCatalog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.canvs.homecontrol.miraie.AcRepository
import `in`.canvs.homecontrol.miraie.AcState
import `in`.canvs.homecontrol.miraie.AcProfile
import `in`.canvs.homecontrol.miraie.TimerMath
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.NetworkWifi2Bar
import androidx.compose.material.icons.filled.NetworkWifi1Bar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import java.text.DateFormat
import java.util.Date
import `in`.canvs.homecontrol.miraie.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Mode presentation shared by tiles and card tint. */
object ModeStyle {
    fun icon(mode: String): ImageVector = when (mode) {
        "cool" -> Icons.Outlined.AcUnit
        "heat" -> Icons.Filled.WbSunny
        "dry" -> Icons.Filled.WaterDrop
        "fan" -> Icons.Filled.Air
        else -> Icons.Filled.Autorenew
    }

    fun tint(mode: String): Color = when (mode) {
        "cool" -> Color(0xFF0A84FF)
        "heat" -> Color(0xFFFF9500)
        "dry" -> Color(0xFF30B0C7)
        "fan" -> Color(0xFF34C759)
        else -> Color(0xFFAF52DE)
    }

    fun fanTitle(speed: String) = if (speed == "medium") "Med" else speed.replaceFirstChar { it.uppercase() }
}

@Composable
fun AcScreen(
    repo: AcRepository,
    showKeys: Boolean,
    onHideKeys: () -> Unit,
    showDiagnostics: Boolean,
    onHideDiagnostics: () -> Unit,
) {
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn && state.devices.isEmpty() && !state.loading) repo.refresh()
    }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = Metrics.screenPadding, end = Metrics.screenPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing),
    ) {
        if (!state.loggedIn) {
            item { LoginCard(repo, state.loading, state.error) }
            return@LazyColumn
        }
        item {
            SectionHeader(
                title = "Air Conditioners",
                detail = when {
                    state.localOnly -> "Local network only"
                    state.cloudConnected -> "Cloud connected"
                    else -> null
                },
                busy = state.loading,
                refreshLabel = "Refresh",
            ) { scope.launch { repo.refresh() } }
        }
        state.error?.let { item { ErrorBanner(it) } }
        if (state.devices.isEmpty() && !state.loading) {
            item {
                EmptyState(
                    icon = Icons.Outlined.AcUnit,
                    title = "No air conditioners",
                    message = "This MirAIe account has no ACs yet. Add one in the MirAIe app, then reload.",
                    actionTitle = "Reload",
                ) { scope.launch { repo.refresh(forceCloud = true) } }
            }
        }
        items(state.devices, key = { it.device.deviceId }) { ac -> AcCard(ac, repo, state.profiles[ac.device.deviceId].orEmpty()) }
        if (showDiagnostics) item { DiagnosticsCard(repo, state.devices, onHideDiagnostics) }
        if (showKeys) item { KeysCard(repo, state.devices, onHideKeys) }
    }
}

@Composable
private fun AcCard(ac: AcState, repo: AcRepository, profiles: List<AcProfile>) {
    val id = ac.device.deviceId
    val tint = ModeStyle.tint(ac.mode)
    HomeCard(tint = if (ac.power) tint else null) {
        // Header: name, room, transport, power
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ac.device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ac.device.space.isNotEmpty()) {
                        Text(ac.device.space, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                    }
                    TransportPill(ac)
                }
            }
            PowerButton(ac.power, label = "${ac.device.name} power", tint = tint) { repo.setPower(id, !ac.power) }
        }

        Spacer(Modifier.height(12.dp))

        // Temperature hero
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (ac.power) "Set to" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    fmt(ac.temperature) + "°",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (ac.power) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "Target temperature ${fmt(ac.temperature)} degrees" },
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                ac.roomTemperature?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Thermostat, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Text("Room ${fmt(it)}°", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row {
                    RoundIconButton(Icons.Filled.Remove, "Decrease temperature", size = 40.dp, enabled = ac.temperature > ac.core.tempMin) {
                        repo.setTemperature(id, ac.temperature - ac.core.tempStep)
                    }
                    RoundIconButton(Icons.Filled.Add, "Increase temperature", size = 40.dp, enabled = ac.temperature < ac.core.tempMax) {
                        repo.setTemperature(id, ac.temperature + ac.core.tempStep)
                    }
                }
            }
        }

        InfoRow(ac)

        Spacer(Modifier.height(8.dp))

        // Mode tiles
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ac.core.modes.forEach { mode ->
                OptionTile(
                    title = mode.replaceFirstChar { it.uppercase() },
                    selected = ac.mode == mode,
                    icon = ModeStyle.icon(mode),
                    tint = ModeStyle.tint(mode),
                    muted = !ac.power,
                    modifier = Modifier.weight(1f),
                ) { repo.setMode(id, mode) }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Fan speed chips
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Outlined.Air, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(2.dp))
            ac.core.fanSpeeds.forEach { speed ->
                OptionTile(
                    title = ModeStyle.fanTitle(speed),
                    selected = ac.fanSpeed == speed,
                    tint = tint,
                    muted = !ac.power,
                    modifier = Modifier.weight(1f),
                ) { repo.setFanSpeed(id, speed) }
            }
        }

        TimerRow(ac, repo)
        ProfilesRow(ac, repo, profiles)
        MoreSection(ac, repo, tint)
    }
}

// ---- timer ---------------------------------------------------------------------------------------

private val TIMER_PRESETS = listOf(30 to "30 min", 60 to "1 h", 120 to "2 h", 240 to "4 h", 480 to "8 h")

/**
 * Turn-on / turn-off timer. Both slots can be set; the selector defaults to "Turn off" while the AC runs
 * and "Turn on" while it's off. Active timers show as pills with Cancel.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimerRow(ac: AcState, repo: AcRepository) {
    val timer = ac.core.timer ?: return
    val id = ac.device.deviceId
    val now = System.currentTimeMillis() / 1000
    val (offUtc, onUtc) = TimerMath.parseStatus(ac.rawStatus[timer.key])
    var custom by remember { mutableStateOf(false) }
    // User's choice; resets to the power-based default when power changes.
    var offSlot by remember(id, ac.power) { mutableStateOf(ac.power) }
    val presets = TIMER_PRESETS.filter { it.first in timer.minMinutes..timer.maxMinutes }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Timer, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.width(184.dp)) {
                OptionTile("Turn on", selected = !offSlot, modifier = Modifier.weight(1f)) { offSlot = false }
                OptionTile("Turn off", selected = offSlot, modifier = Modifier.weight(1f)) { offSlot = true }
            }
            Spacer(Modifier.width(6.dp))
            Text("in…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            presets.forEach { (minutes, label) ->
                OptionTile(label, selected = false, modifier = Modifier.weight(1f)) { repo.setTimer(id, offSlot, minutes) }
            }
            OptionTile("Custom", selected = false, modifier = Modifier.weight(1.3f)) { custom = true }
        }
        val active = buildList {
            if (TimerMath.remaining(offUtc, now) > 0) add(true to offUtc)
            if (TimerMath.remaining(onUtc, now) > 0) add(false to onUtc)
        }
        if (active.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                active.forEach { (isOff, utc) ->
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utc * 1000))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (isOff) "Off at " else "On at ") + time,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = { repo.setTimer(id, isOff, -1) }) { Text("Cancel") }
                    }
                }
            }
        }
    }

    if (custom) {
        val steps = ((timer.maxMinutes - timer.minMinutes) / timer.stepMinutes).coerceAtLeast(1)
        var value by remember { mutableFloatStateOf(60f.coerceIn(timer.minMinutes.toFloat(), timer.maxMinutes.toFloat())) }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(if (offSlot) "Turn off in" else "Turn on in") },
            text = {
                Column {
                    Text(durationLabel(value.toInt()), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = value,
                        onValueChange = { value = it },
                        valueRange = timer.minMinutes.toFloat()..timer.maxMinutes.toFloat(),
                        steps = steps - 1,
                        modifier = Modifier.semantics { contentDescription = "Timer duration" },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { repo.setTimer(id, offSlot, value.toInt()); custom = false }) { Text("Set timer") }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } },
        )
    }
}

private fun durationLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

// ---- profiles ------------------------------------------------------------------------------------

/** Saved local profiles: tap to apply, long-press to delete. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ProfilesRow(ac: AcState, repo: AcRepository, profiles: List<AcProfile>) {
    if (profiles.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    var deleting by remember { mutableStateOf<AcProfile?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bookmark, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Profiles", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            profiles.forEach { p ->
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(Metrics.tileRadius))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .combinedClickable(
                            onClick = { repo.applyProfile(ac.device.deviceId, p) },
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); deleting = p },
                            onLongClickLabel = "Delete profile",
                        )
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(p.name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("This profile is removed from this phone only.") },
            confirmButton = { TextButton(onClick = { repo.deleteProfile(ac.device.deviceId, p.name); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SaveProfileButton(ac: AcState, repo: AcRepository) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    TextButton(onClick = { name = ""; naming = true }) {
        Icon(Icons.Outlined.Bookmark, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Save as profile")
    }
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save profile") },
            text = {
                Column {
                    Text(
                        "Saves power, mode, temperature, fan, swing and display.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name, e.g. Night") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { repo.saveProfile(ac.device.deviceId, name); naming = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Status info from real status keys (PROTOCOL.md §1.8): Wi-Fi signal, filter, errors, warnings,
 * plus humidity / PM2.5 / power only when the model lists them and the AC reports them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoRow(ac: AcState) {
    val st = ac.rawStatus
    data class Chip(val icon: ImageVector, val text: String, val color: Color? = null)
    val chips = buildList {
        st["errors"]?.toString()?.trim()?.takeIf { isFault(it) }?.let { add(Chip(Icons.Filled.ErrorOutline, "Error $it", Color(0xFFFF3B30))) }
        st["warnings"]?.toString()?.trim()?.takeIf { isFault(it) }?.let { add(Chip(Icons.Outlined.Warning, "Warning $it", Color(0xFFFF9500))) }
        if (st["filterCleaningRequired"]?.toString().equals("true", ignoreCase = true)) {
            val level = st["filterDustLevel"]?.toString()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
            add(Chip(Icons.Outlined.CleaningServices, "Clean filter" + (level?.let { " · level $it" } ?: ""), Color(0xFFFF9500)))
        } else {
            st["filterDustLevel"]?.toString()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }?.let { add(Chip(Icons.Outlined.FilterAlt, "Filter level $it")) }
        }
        ac.capabilities.filter { it.kind == CapKind.Status }.forEach { spec ->
            val text = ac.statusOf(spec)?.toString()?.trim() ?: return@forEach
            when (spec.name) {
                "RoomHumidity" -> add(Chip(Icons.Outlined.WaterDrop, "${num(text)}% humidity"))
                "PM25Sensor" -> add(Chip(Icons.Outlined.Grain, "PM2.5 ${num(text)}"))
                "CurrentPowerConsumption" -> add(Chip(Icons.Outlined.Bolt, "${num(text)} W"))
            }
        }
        st["rssi"]?.toString()?.toDoubleOrNull()?.toInt()?.let { rssi ->
            val (icon, label) = when {
                rssi >= -60 -> Icons.Filled.SignalWifi4Bar to "Strong"
                rssi >= -72 -> Icons.Filled.NetworkWifi2Bar to "Fair"
                else -> Icons.Filled.NetworkWifi1Bar to "Weak"
            }
            add(Chip(icon, "Wi-Fi $label ($rssi dBm)"))
        }
    }
    if (chips.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { c ->
            if (c.color != null) {
                StatusPill(c.text, c.color, c.icon)
            } else {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(Metrics.tileRadius))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(c.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(c.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun isFault(text: String): Boolean =
    text.isNotEmpty() && text !in setOf("0", "[]", "{}", "none", "None", "null", "0.0")

private fun num(text: String): String = text.toDoubleOrNull()?.let { fmt(it) } ?: text

/** Extra controls the model lists; collapsed by default. */
@Composable
private fun MoreSection(ac: AcState, repo: AcRepository, tint: Color) {
    val density = LocalDensity.current.density
    var open by rememberSaveable(ac.device.deviceId) { mutableStateOf(false) }
    val controls = ac.capabilities.filter { it.kind != CapKind.Status }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(Metrics.tileRadius))
            .pressable { open = !open }
            .semantics { contentDescription = if (open) "Hide more controls" else "Show more controls" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (controls.isEmpty()) "More" else "More controls",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (controls.isNotEmpty() && !open) {
            Text("${controls.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
    }

    AnimatedVisibility(open, enter = revealEnter(density), exit = revealExit(density)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ac.modelJson == null) {
                Text(
                    "Extra controls appear after the AC's model info loads (Refresh while signed in).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            if (controls.isEmpty()) {
                Text("This AC model has no extra controls.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            val id = ac.device.deviceId
            val toggles = controls.filter { it.kind == CapKind.Toggle }
            if (toggles.isNotEmpty()) {
                toggles.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { spec ->
                            val on = ac.statusOf(spec)?.toString().equals("on", ignoreCase = true)
                            OptionTile(
                                title = spec.label,
                                selected = on,
                                tint = tint,
                                muted = !ac.power,
                                modifier = Modifier.weight(1f).semantics { stateDescription = if (on) "On" else "Off" },
                            ) { repo.sendRaw(id, spec.key, if (on) "off" else "on", spec.ki) }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            controls.filter { it.kind == CapKind.Choice }.forEach { spec ->
                OptionGroup(spec.label) {
                    spec.values.forEach { v ->
                        OptionTile(
                            title = CapabilityCatalog.choiceLabel(v),
                            selected = ac.statusOf(spec)?.toString().equals(v, ignoreCase = true),
                            tint = tint,
                            muted = !ac.power,
                            modifier = Modifier.weight(1f),
                        ) { repo.sendRaw(id, spec.key, v, spec.ki) }
                    }
                }
            }
            val cleaning = ac.spec("EvaporatorClean")?.let { ac.statusOf(it)?.toString().equals("on", ignoreCase = true) } == true
            val lastSwing = controls.lastOrNull { it.name == "VerticalSwing" || it.name == "HorizontalSwing" }?.name
            controls.filter { it.kind == CapKind.IntChoice }.forEach { spec ->
                val isSwing = spec.name == "VerticalSwing" || spec.name == "HorizontalSwing"
                val locked = isSwing && cleaning
                // Up to 6 options fit one row; longer sets split evenly over rows (no orphans).
                val n = spec.intValues.size
                val perRow = if (n <= 6) n.coerceAtLeast(1) else (n + 1) / 2
                spec.intValues.chunked(perRow).forEachIndexed { i, chunk ->
                    OptionGroup(if (i == 0) spec.label else null) {
                        chunk.forEach { option ->
                            OptionTile(
                                title = CapabilityCatalog.optionLabel(spec, option),
                                selected = CapabilityCatalog.matches(spec, option, ac.statusOf(spec)),
                                tint = tint,
                                muted = !ac.power,
                                enabled = !locked,
                                modifier = Modifier.weight(1f),
                            ) { repo.sendRaw(id, spec.key, CapabilityCatalog.payload(spec, option), spec.ki) }
                        }
                        repeat(perRow - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (locked && spec.name == lastSwing) {
                    Text(
                        "Swing is locked while Clean runs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SaveProfileButton(ac, repo)
        }
    }
}

@Composable
private fun OptionGroup(title: String?, content: @Composable RowScope.() -> Unit) {
    Column {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** Per-AC model capabilities and last raw status, with a redacted copy for sharing. */
@Composable
private fun DiagnosticsCard(repo: AcRepository, devices: List<AcState>, onHide: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BugReport, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("AC diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onHide) { Text("Hide") }
        }
        Text(
            "Safe to share: no password, tokens or full AC keys are included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (devices.isEmpty()) {
            Text("No ACs loaded yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        devices.forEach { ac ->
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Metrics.tileRadius))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        .padding(10.dp),
                ) {
                    Text(ac.device.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    KeyRow(
                        "Model",
                        ac.device.modelId
                            ?: ac.modelJson?.let { runCatching { org.json.JSONObject(it).optString("modelId") }.getOrNull() }?.ifEmpty { null }
                            ?: "unknown",
                    )
                    KeyRow("Link", ac.transport.name + if (ac.udpSupported == false) " (no LAN support)" else "")
                    KeyRow("LAN", (if (ac.localKeys != null) "key available" else "no key") + " · " + ac.lastLocalDiscovery)
                    ac.rawStatus["V"]?.let { KeyRow("FW", it.toString()) }
                    KeyRow(
                        "Caps",
                        if (ac.modelJson == null) "model not loaded"
                        else ac.capabilities.joinToString(", ") { "${it.name}=${it.key}" }.ifEmpty { "none beyond basics" },
                    )
                    KeyRow("Status", org.json.JSONObject(ac.rawStatus.mapValues { it.value ?: org.json.JSONObject.NULL }).toString())
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        FilledTonalButton(
            onClick = {
                copySecret(context, repo.diagnosticsJson(version), label = "AC diagnostics", toast = "Diagnostics copied")
                copied = true
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (copied) "Copied" else "Copy diagnostics")
        }
    }
}

@Composable
private fun TransportPill(ac: AcState) {
    when {
        !ac.online -> StatusPill("Offline", Color(0xFFFF3B30), Icons.Filled.WifiOff)
        ac.transport == Transport.Local -> StatusPill("Local", Color(0xFF34C759), Icons.Filled.Wifi)
        ac.transport == Transport.Cloud -> StatusPill("Cloud", Color(0xFF0A84FF), Icons.Filled.Cloud)
        else -> StatusPill("Unreachable", Color(0xFFFF3B30), Icons.Filled.WifiOff)
    }
}

@Composable
private fun LoginCard(repo: AcRepository, loading: Boolean, error: String?) {
    val scope = rememberCoroutineScope()
    var login by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { if (login.isNotBlank() && password.isNotEmpty() && !loading) scope.launch { repo.login(login.trim(), password) } }

    HomeCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Icon(Icons.Outlined.AcUnit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(8.dp))
            Text("Connect your Panasonic AC", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Sign in with your MirAIe account once. After that, your AC is controlled over your Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = login,
            onValueChange = { login = it },
            label = { Text("Mobile number or email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(10.dp))
            ErrorBanner(it)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { submit() },
            enabled = login.isNotBlank() && password.isNotEmpty() && !loading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Signing in…")
            } else {
                Text("Sign in")
            }
        }
        Spacer(Modifier.height(4.dp))
        KeyCodeImport(repo)
        Text(
            "10-digit mobile numbers are sent as +91. Your password is stored encrypted on this phone.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Sign-in alternative: paste a key code (from this app or the Mac app) to go local-only. */
@Composable
private fun KeyCodeImport(repo: AcRepository) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var open by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(onClick = { open = !open }) { Text(if (open) "Cancel" else "Use a key code instead") }
        AnimatedVisibility(open, enter = revealEnter(density), exit = revealExit(density)) {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Key code") },
                    placeholder = { Text("miraie-local-v1:…") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { code = it }
                    }) { Text("Paste") }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { repo.importKeyCode(code) }, enabled = code.isNotBlank()) { Text("Use key code") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Shows each AC's LAN key so it can be backed up and restored without signing in. */
@Composable
private fun KeysCard(repo: AcRepository, devices: List<AcState>, onHide: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val withKeys = devices.filter { it.localKeys != null }

    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("AC keys", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = onHide) { Text("Hide") }
        }
        Text(
            "These keys control your ACs over Wi-Fi with no Panasonic sign-in. Anyone on your network who has them can control your ACs, so keep them private.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (withKeys.isEmpty()) {
            Text(
                "No keys yet. Your AC model may not support local control, or keys haven't been fetched. Try Refresh.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        withKeys.forEach { ac ->
            val k = ac.localKeys!!
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Metrics.tileRadius))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                        .padding(10.dp),
                ) {
                    Text(ac.device.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    KeyRow("MAC", k.mac)
                    KeyRow("IP", k.ip ?: "unknown")
                    KeyRow("Key", k.keyHex)
                    KeyRow("IV", k.ivHex)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (withKeys.isNotEmpty()) {
            FilledTonalButton(
                onClick = { repo.exportKeyCode()?.let { copySecret(context, it); copied = true } },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "Copied" else "Copy key code")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Paste the code into “Use a key code instead” on the sign-in screen, on this phone, another phone or the Mac app.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeyRow(label: String, value: String) {
    Row(Modifier.padding(top = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        Text(value, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
    }
}

private fun fmt(t: Double): String = if (t % 1.0 == 0.0) t.toInt().toString() else "%.1f".format(t)
