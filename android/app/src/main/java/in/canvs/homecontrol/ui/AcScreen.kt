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
import `in`.canvs.homecontrol.miraie.AirQuality
import `in`.canvs.homecontrol.miraie.SleepProfile
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.heightIn
import `in`.canvs.homecontrol.miraie.CapabilitySpec
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Checkroom

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
    listState: LazyListState = rememberLazyListState(),
    onOpen: (AcPage) -> Unit = {},
) {
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn && state.devices.isEmpty() && !state.loading) repo.refresh()
    }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        state = listState,
        contentPadding = PaddingValues(start = Metrics.screenPadding, end = Metrics.screenPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing),
    ) {
        if (!state.loggedIn) {
            item { LoginCard(repo, state.loading, state.error) }
            return@LazyColumn
        }
        item {
            SectionHeader(
                title = "Air conditioners",
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
        items(state.devices, key = { it.device.deviceId }) { ac ->
            AcCard(ac, repo, state.profiles[ac.device.deviceId].orEmpty(), state.sleepProfiles[ac.device.deviceId], onOpen)
        }
    }
}

@Composable
private fun AcCard(
    ac: AcState,
    repo: AcRepository,
    profiles: List<AcProfile>,
    sleepProfiles: List<SleepProfile>?,
    onOpen: (AcPage) -> Unit,
) {
    val id = ac.device.deviceId
    val tint = ModeStyle.tint(ac.mode)
    HomeCard(tint = if (ac.power) tint else null) {
        // Name · room · transport · Wi-Fi, and power
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ac.device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ac.device.space.isNotEmpty()) {
                        Text(ac.device.space, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                    }
                    TransportPill(ac)
                    WifiGlyph(ac)
                }
            }
            PowerButton(ac.power, label = "${ac.device.name} power", tint = tint) { repo.setPower(id, !ac.power) }
        }

        Spacer(Modifier.height(8.dp))

        // Temperature hero
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    fmt(ac.temperature) + "°",
                    fontSize = 48.sp,
                    lineHeight = 52.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.SansSerif,
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    color = if (ac.power) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = "Target temperature ${fmt(ac.temperature)} degrees" },
                )
                val room = roomLine(ac)
                Text(
                    if (ac.power) room ?: "Set temperature" else listOfNotNull("Off", room).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            RoundIconButton(Icons.Filled.Remove, "Decrease temperature", size = 40.dp, enabled = ac.temperature > ac.core.tempMin) {
                repo.setTemperature(id, ac.temperature - ac.core.tempStep)
            }
            RoundIconButton(Icons.Filled.Add, "Increase temperature", size = 40.dp, enabled = ac.temperature < ac.core.tempMax) {
                repo.setTemperature(id, ac.temperature + ac.core.tempStep)
            }
        }

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

        Spacer(Modifier.height(4.dp))

        // Fan speed chips
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Air, "Fan speed", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
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

        StatusStrip(ac, repo, sleepProfiles)
        ProfileChips(ac, repo, profiles)
        ActionBar(ac, sleepProfiles, onOpen)
    }
}

/** "Room 32° · 62% · 840 W" from the values the AC actually reports. */
private fun roomLine(ac: AcState): String? {
    val parts = buildList {
        ac.roomTemperature?.let { add("Room ${fmt(it)}°") }
        ac.capabilities.filter { it.kind == CapKind.Status }.forEach { spec ->
            val text = ac.statusOf(spec)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            when (spec.name) {
                "RoomHumidity" -> add("${num(text)}% humidity")
                "CurrentPowerConsumption" -> add("${num(text)} W")
            }
        }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

/** Wi-Fi strength as a small glyph next to the transport badge (not a pill). */
@Composable
private fun WifiGlyph(ac: AcState) {
    val rssi = ac.rawStatus["rssi"]?.toString()?.toDoubleOrNull()?.toInt() ?: return
    val (icon, label) = when {
        rssi >= -60 -> Icons.Filled.SignalWifi4Bar to "strong"
        rssi >= -72 -> Icons.Filled.NetworkWifi2Bar to "fair"
        else -> Icons.Filled.NetworkWifi1Bar to "weak"
    }
    Spacer(Modifier.width(6.dp))
    Icon(
        icon,
        contentDescription = "Wi-Fi signal $label, $rssi dBm",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(14.dp),
    )
}

// ---- timer ---------------------------------------------------------------------------------------

private val TIMER_PRESETS = listOf(30 to "30 min", 60 to "1 h", 120 to "2 h", 240 to "4 h", 480 to "8 h")

/**
 * Timer page body. Turn on / Turn off selector (defaults to the off timer while the AC runs), presets,
 * custom duration, and active timers with Cancel. Both slots can be set at once.
 */
@Composable
internal fun TimerPanel(ac: AcState, repo: AcRepository) {
    val timer = ac.core.timer
    if (timer == null) {
        EmptyState(icon = Icons.Outlined.Timer, title = "No timer", message = "This AC model doesn't support an on/off timer.")
        return
    }
    val id = ac.device.deviceId
    val now = System.currentTimeMillis() / 1000
    val (offUtc, onUtc) = TimerMath.parseStatus(ac.rawStatus[timer.key])
    var custom by remember { mutableStateOf(false) }
    var offSlot by remember(id, ac.power) { mutableStateOf(ac.power) }
    val presets = TIMER_PRESETS.filter { it.first in timer.minMinutes..timer.maxMinutes }
    val active = buildList {
        if (TimerMath.remaining(offUtc, now) > 0) add(true to offUtc)
        if (TimerMath.remaining(onUtc, now) > 0) add(false to onUtc)
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing)) {
        if (active.isNotEmpty()) {
            HomeCard {
                CardTitle("Active")
                active.forEachIndexed { i, (isOff, utc) ->
                    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(utc * 1000))
                    val left = durationLabel(((utc - now) / 60).toInt().coerceAtLeast(1))
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Timer, null, tint = if (isOff) Color(0xFFFF9500) else Color(0xFF34C759), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text((if (isOff) "Turns off at " else "Turns on at ") + time, style = MaterialTheme.typography.bodyLarge)
                            Text("in $left", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { repo.setTimer(id, isOff, -1) }) { Text("Cancel") }
                    }
                    if (i < active.lastIndex) HorizontalDivider()
                }
            }
        }
        HomeCard {
            CardTitle("Set a timer")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OptionTile("Turn on", selected = !offSlot, modifier = Modifier.weight(1f)) { offSlot = false }
                OptionTile("Turn off", selected = offSlot, modifier = Modifier.weight(1f)) { offSlot = true }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (offSlot) "Turn the AC off in…" else "Turn the AC on in…",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { (minutes, label) ->
                    OptionTile(label, selected = false, modifier = Modifier.weight(1f)) { repo.setTimer(id, offSlot, minutes) }
                }
            }
            Spacer(Modifier.height(4.dp))
            OptionTile("Custom…", selected = false, modifier = Modifier.fillMaxWidth()) { custom = true }
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

/** Saved local profiles on the card: one tap applies. Hidden when none are saved. */
@Composable
private fun ProfileChips(ac: AcState, repo: AcRepository, profiles: List<AcProfile>) {
    if (profiles.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Bookmark, "Profiles", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        profiles.forEach { p ->
            Box(
                Modifier
                    .height(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .pressable { repo.applyProfile(ac.device.deviceId, p) }
                    .semantics { contentDescription = "Apply profile ${p.name}" }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

/** More page: saved profiles with apply and delete, plus "Save current settings". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProfilesCard(ac: AcState, repo: AcRepository, profiles: List<AcProfile>) {
    var deleting by remember { mutableStateOf<AcProfile?>(null) }
    HomeCard {
        CardTitle("Profiles")
        Text(
            "Profiles save power, mode, temperature, fan, swing and display on this phone. Tap one on the AC card to apply it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        profiles.forEach { p ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bookmark, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { repo.applyProfile(ac.device.deviceId, p) }) { Text("Apply") }
                RoundIconButton(Icons.Outlined.Delete, "Delete profile ${p.name}", size = 36.dp) { deleting = p }
            }
        }
        SaveProfileButton(ac, repo)
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
        Text("Save current settings as profile")
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
 * Status pills, shown only when something matters (docs/UI.md): active timers, a running sleep profile,
 * Clean running, filter alert, errors, warnings, PM2.5. One horizontally scrolling row; never wraps.
 */
@Composable
private fun StatusStrip(ac: AcState, repo: AcRepository, sleepProfiles: List<SleepProfile>?) {
    val st = ac.rawStatus
    val id = ac.device.deviceId
    val now = System.currentTimeMillis() / 1000
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val timerKey = ac.core.timer?.key
    val (offUtc, onUtc) = if (timerKey != null) TimerMath.parseStatus(st[timerKey]) else (-1L to -1L)
    val running = sleepProfiles?.firstOrNull { it.isActive(st[SleepProfile.KEY]?.toString()) }
    val cleaning = ac.spec("EvaporatorClean")?.let { ac.statusOf(it)?.toString().equals("on", ignoreCase = true) } == true

    data class Pill(val text: String, val color: Color, val icon: ImageVector, val dismissLabel: String? = null, val onDismiss: (() -> Unit)? = null)
    val pills = buildList {
        st["errors"]?.toString()?.trim()?.takeIf { isFault(it) }?.let { add(Pill("Error $it", Color(0xFFFF3B30), Icons.Filled.ErrorOutline)) }
        st["warnings"]?.toString()?.trim()?.takeIf { isFault(it) }?.let { add(Pill("Warning $it", Color(0xFFFF9500), Icons.Outlined.Warning)) }
        if (TimerMath.remaining(offUtc, now) > 0) {
            add(Pill("Off at ${time.format(Date(offUtc * 1000))}", Color(0xFFFF9500), Icons.Outlined.Timer, "Cancel turn-off timer") { repo.setTimer(id, true, -1) })
        }
        if (TimerMath.remaining(onUtc, now) > 0) {
            add(Pill("On at ${time.format(Date(onUtc * 1000))}", Color(0xFF34C759), Icons.Outlined.Timer, "Cancel turn-on timer") { repo.setTimer(id, false, -1) })
        }
        if (running != null) {
            add(Pill(running.displayName, Color(0xFFAF52DE), Icons.Outlined.Bedtime, "Stop sleep profile") { repo.setSleepProfileActive(id, running, false) })
        }
        if (cleaning) add(Pill("Cleaning", Color(0xFF30B0C7), Icons.Outlined.CleaningServices))
        if (st["filterCleaningRequired"]?.toString().equals("true", ignoreCase = true)) {
            add(Pill("Clean filter", Color(0xFFFF9500), Icons.Outlined.FilterAlt))
        }
        ac.capabilities.firstOrNull { it.name == "PM25Sensor" }?.let { spec ->
            ac.statusOf(spec)?.toString()?.toDoubleOrNull()?.toInt()?.let { pm -> AirQuality.band(pm)?.let { band ->
                add(Pill("PM2.5 $pm · ${band.label}", aqiColor(band), Icons.Outlined.Grain))
            } }
        }
    }
    if (pills.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        pills.forEach { p -> DismissiblePill(p.text, p.color, p.icon, p.dismissLabel, p.onDismiss) }
    }
}

private fun aqiColor(band: AirQuality.Band): Color = when (band) {
    AirQuality.Band.Excellent -> Color(0xFF34C759)
    AirQuality.Band.Good -> Color(0xFF30B0C7)
    AirQuality.Band.Moderate -> Color(0xFFFFCC00)
    AirQuality.Band.Poor -> Color(0xFFFF9500)
    AirQuality.Band.Unhealthy -> Color(0xFFFF3B30)
    AirQuality.Band.Hazardous -> Color(0xFFAF52DE)
}

private fun isFault(text: String): Boolean =
    text.isNotEmpty() && text !in setOf("0", "[]", "{}", "none", "None", "null", "0.0")

private fun num(text: String): String = text.toDoubleOrNull()?.let { fmt(it) } ?: text

/** Action bar: opens the detail pages. A dot marks a page with something active. */
@Composable
private fun ActionBar(ac: AcState, sleepProfiles: List<SleepProfile>?, onOpen: (AcPage) -> Unit) {
    val id = ac.device.deviceId
    val st = ac.rawStatus
    val now = System.currentTimeMillis() / 1000
    val timerActive = ac.core.timer?.let { t ->
        val (off, on) = TimerMath.parseStatus(st[t.key])
        TimerMath.remaining(off, now) > 0 || TimerMath.remaining(on, now) > 0
    } == true
    val sleepActive = sleepProfiles?.any { it.isActive(st[SleepProfile.KEY]?.toString()) } == true
    val moreAlert = st["filterCleaningRequired"]?.toString().equals("true", ignoreCase = true)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (ac.core.timer != null) ActionButton("Timer", Icons.Outlined.Timer, Modifier.weight(1f), active = timerActive) { onOpen(AcPage.Timer(id)) }
        if (ac.spec("SleepProfile") != null) ActionButton("Sleep", Icons.Outlined.Bedtime, Modifier.weight(1f), active = sleepActive, cloud = true) { onOpen(AcPage.Sleep(id)) }
        ActionButton("Air", Icons.Outlined.Air, Modifier.weight(1f)) { onOpen(AcPage.Air(id)) }
        ActionButton("Energy", Icons.Outlined.Bolt, Modifier.weight(1f), cloud = true) { onOpen(AcPage.Energy(id)) }
        ActionButton("More", Icons.Filled.MoreHoriz, Modifier.weight(1f), active = moreAlert) { onOpen(AcPage.More(id)) }
    }
}

/** Air page: swing, display, capacity and feature toggles the model lists. */
internal fun airContent(scope: LazyListScope, ac: AcState, repo: AcRepository) = with(scope) {
    val tint = ModeStyle.tint(ac.mode)
    if (ac.modelJson == null) {
        item {
            EmptyState(
                icon = Icons.Outlined.Air,
                title = "Model info not loaded",
                message = "Extra controls appear after the AC's model info loads (Refresh while signed in).",
            )
        }
        return@with
    }
    val controls = ac.capabilities.filter { it.kind != CapKind.Status }
    if (controls.isEmpty()) {
        item { EmptyState(icon = Icons.Outlined.Air, title = "No extra controls", message = "This AC model has no extra controls.") }
        return@with
    }
    val id = ac.device.deviceId
    val swings = controls.filter { it.kind == CapKind.IntChoice && (it.name == "VerticalSwing" || it.name == "HorizontalSwing") }
    val cleaning = ac.spec("EvaporatorClean")?.let { ac.statusOf(it)?.toString().equals("on", ignoreCase = true) } == true
    if (swings.isNotEmpty()) {
        item {
            HomeCard {
                CardTitle("Swing")
                swings.forEachIndexed { i, spec -> IntChoiceGroup(ac, repo, spec, tint, locked = cleaning, showTitle = true); if (i < swings.lastIndex) Spacer(Modifier.height(10.dp)) }
                if (cleaning) {
                    Text(
                        "Swing is locked while Clean runs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
    val choices = controls.filter { it.kind == CapKind.Choice }
    val others = controls.filter { it.kind == CapKind.IntChoice && it !in swings }
    if (choices.isNotEmpty() || others.isNotEmpty()) {
        item {
            HomeCard {
                CardTitle("Display and capacity")
                choices.forEachIndexed { i, spec ->
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
                    if (i < choices.lastIndex || others.isNotEmpty()) Spacer(Modifier.height(10.dp))
                }
                others.forEachIndexed { i, spec ->
                    IntChoiceGroup(ac, repo, spec, tint, locked = false, showTitle = true)
                    if (i < others.lastIndex) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
    val toggles = controls.filter { it.kind == CapKind.Toggle || it.kind == CapKind.Cycle }
    if (toggles.isNotEmpty()) {
        item {
            HomeCard {
                CardTitle("Features")
                toggles.chunked(3).forEachIndexed { r, row ->
                    if (r > 0) Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { spec ->
                            val value = ac.statusOf(spec)?.toString()?.lowercase()
                            val on = value != null && value != "off" && (spec.kind == CapKind.Cycle || value == "on")
                            OptionTile(
                                title = if (spec.kind == CapKind.Cycle) "${spec.label}: ${CapabilityCatalog.choiceLabel(value ?: "off")}" else spec.label,
                                selected = on,
                                icon = featureIcon(spec.name),
                                tint = tint,
                                muted = !ac.power,
                                modifier = Modifier.weight(1f).semantics { stateDescription = CapabilityCatalog.choiceLabel(value ?: "off") },
                            ) {
                                if (spec.kind == CapKind.Cycle) repo.cycle(id, spec) else repo.setToggle(id, spec, !on)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Icon for a model feature toggle, so feature tiles read like the mode tiles. */
private fun featureIcon(name: String): ImageVector = when (name) {
    "PowerfulMode" -> Icons.Outlined.Bolt
    "ECOMode", "EconomySensor" -> Icons.Outlined.Eco
    "EvaporatorClean" -> Icons.Outlined.CleaningServices
    "BuzzerControl" -> Icons.AutoMirrored.Outlined.VolumeUp
    "NanoeG", "NanoeX", "NanoeGA", "Ionizer" -> Icons.Outlined.Grain
    "JetStream", "TurboMode" -> Icons.Outlined.Speed
    "EcoNavi", "MotionSensor" -> Icons.Outlined.Sensors
    "Humidifier" -> Icons.Outlined.WaterDrop
    "LaundryMode" -> Icons.Outlined.Checkroom
    else -> Icons.Outlined.Tune
}

/** Integer choices (swing positions, capacity). Up to 6 per row; longer sets split evenly (no orphans). */
@Composable
private fun IntChoiceGroup(ac: AcState, repo: AcRepository, spec: CapabilitySpec, tint: Color, locked: Boolean, showTitle: Boolean) {
    val n = spec.intValues.size
    val perRow = if (n <= 6) n.coerceAtLeast(1) else (n + 1) / 2
    spec.intValues.chunked(perRow).forEachIndexed { i, chunk ->
        if (i > 0) Spacer(Modifier.height(4.dp))
        OptionGroup(if (i == 0 && showTitle) spec.label else null) {
            chunk.forEach { option ->
                OptionTile(
                    title = CapabilityCatalog.optionLabel(spec, option),
                    selected = CapabilityCatalog.matches(spec, option, ac.statusOf(spec)),
                    tint = tint,
                    muted = !ac.power,
                    enabled = !locked,
                    modifier = Modifier.weight(1f),
                ) { repo.sendRaw(ac.device.deviceId, spec.key, CapabilityCatalog.payload(spec, option), spec.ki) }
            }
            repeat(perRow - chunk.size) { Spacer(Modifier.weight(1f)) }
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
internal fun DiagnosticsCard(repo: AcRepository, devices: List<AcState>, onHide: (() -> Unit)?) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    var open by rememberSaveable { mutableStateOf(false) }
    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BugReport, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("AC diagnostics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onHide != null) TextButton(onClick = onHide) { Text("Hide") }
            else TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        if (onHide != null || open) {
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
internal fun KeysCard(repo: AcRepository, devices: List<AcState>, onHide: (() -> Unit)?) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }
    val withKeys = devices.filter { it.localKeys != null }

    var open by rememberSaveable { mutableStateOf(false) }
    HomeCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("AC keys", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onHide != null) TextButton(onClick = onHide) { Text("Hide") }
            else TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        if (onHide != null || open) {
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
}

@Composable
private fun KeyRow(label: String, value: String) {
    Row(Modifier.padding(top = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(48.dp))
        Text(value, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
    }
}

private fun fmt(t: Double): String = if (t % 1.0 == 0.0) t.toInt().toString() else "%.1f".format(t)
