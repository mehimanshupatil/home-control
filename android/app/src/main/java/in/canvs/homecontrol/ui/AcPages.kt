package `in`.canvs.homecontrol.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.canvs.homecontrol.miraie.AcNotification
import `in`.canvs.homecontrol.miraie.AcRepository
import `in`.canvs.homecontrol.miraie.AcState
import `in`.canvs.homecontrol.miraie.AcUiState
import `in`.canvs.homecontrol.miraie.EnergyGrain
import `in`.canvs.homecontrol.miraie.EnergyWindow
import `in`.canvs.homecontrol.miraie.FilterHealth
import `in`.canvs.homecontrol.miraie.FirmwareInfo
import `in`.canvs.homecontrol.miraie.Schedule
import `in`.canvs.homecontrol.miraie.SleepProfile
import `in`.canvs.homecontrol.miraie.SleepRule
import `in`.canvs.homecontrol.miraie.SleepSpec
import `in`.canvs.homecontrol.miraie.Space
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Full-screen AC pages opened from a card or the menu. Encoded as "kind:deviceId" for rememberSaveable. */
sealed interface AcPage {
    data class Energy(val deviceId: String) : AcPage
    data class Sleep(val deviceId: String) : AcPage
    data class Settings(val deviceId: String) : AcPage
    data object Notifications : AcPage

    fun encode(): String = when (this) {
        is Energy -> "energy:$deviceId"
        is Sleep -> "sleep:$deviceId"
        is Settings -> "settings:$deviceId"
        Notifications -> "notifications:"
    }

    companion object {
        fun decode(s: String?): AcPage? {
            val (kind, id) = s?.split(":", limit = 2)?.takeIf { it.size == 2 } ?: return null
            return when (kind) {
                "energy" -> Energy(id)
                "sleep" -> Sleep(id)
                "settings" -> Settings(id)
                "notifications" -> Notifications
                else -> null
            }
        }
    }
}

@Composable
fun AcPageHost(page: AcPage, repo: AcRepository, onBack: () -> Unit) {
    val state by repo.state.collectAsStateWithLifecycle()
    val ac = (page as? AcPage.Energy)?.deviceId ?: (page as? AcPage.Sleep)?.deviceId ?: (page as? AcPage.Settings)?.deviceId
    val device = ac?.let { id -> state.devices.firstOrNull { it.device.deviceId == id } }
    when (page) {
        is AcPage.Energy -> PageScaffold("Energy", device?.device?.name, cloud = true, onBack = onBack) {
            if (device == null) item { MissingAc() } else if (!state.cloudAvailable) item { NeedsSignIn() } else item { EnergyContent(repo, device) }
        }
        is AcPage.Sleep -> PageScaffold("Sleep profiles", device?.device?.name, cloud = true, onBack = onBack) {
            if (device == null) item { MissingAc() } else if (!state.cloudAvailable) item { NeedsSignIn() } else sleepContent(this, repo, device, state)
        }
        is AcPage.Settings -> PageScaffold("AC settings", device?.device?.name, cloud = false, onBack = onBack) {
            if (device == null) item { MissingAc() } else settingsContent(this, repo, device, state)
        }
        AcPage.Notifications -> PageScaffold("AC notifications", null, cloud = true, onBack = onBack) {
            if (!state.cloudAvailable) item { NeedsSignIn() } else item { NotificationsContent(repo) }
        }
    }
}

@Composable
private fun PageScaffold(title: String, subtitle: String?, cloud: Boolean, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = Metrics.screenPadding, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", size = 40.dp, onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (cloud) CloudCaption()
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Metrics.screenPadding, end = Metrics.screenPadding, bottom = 24.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing),
            content = content,
        )
    }
}

/** Marks a feature that needs the MirAIe cloud. */
@Composable
fun CloudCaption(text: String = "Cloud") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Uses the MirAIe cloud" }) {
        Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NeedsSignIn() {
    EmptyState(
        icon = Icons.Outlined.CloudOff,
        title = "Needs MirAIe sign-in",
        message = "This feature uses the MirAIe cloud. Sign in, and turn off Local network only, to use it.",
    )
}

@Composable
private fun MissingAc() {
    EmptyState(icon = Icons.Outlined.CloudOff, title = "AC not found", message = "Go back and refresh the AC list.")
}

private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

// ---- energy ----------------------------------------------------------------------------------------

@Composable
private fun EnergyContent(repo: AcRepository, ac: AcState) {
    var grain by remember { mutableStateOf(EnergyGrain.Daily) }
    var load by remember { mutableStateOf<Load<EnergyWindow>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(grain, reload) {
        load = Load.Loading
        load = runCatching { repo.energy(ac.device.deviceId, grain) }
            .fold({ Load.Done(it) }, { Load.Failed(it.message ?: "Couldn't load energy data") })
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EnergyGrain.entries.forEach { g ->
                OptionTile(g.label, selected = grain == g, modifier = Modifier.weight(1f)) { grain = g }
            }
        }
        HomeCard {
            when (val l = load) {
                Load.Loading -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                is Load.Failed -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ErrorBanner(l.message)
                    TextButton(onClick = { reload++ }) { Text("Try again") }
                }
                is Load.Done -> EnergyChart(l.value)
            }
        }
        Text(
            "Energy use as reported by the MirAIe cloud, in kWh. Days without data show as zero.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EnergyChart(window: EnergyWindow) {
    val total = window.buckets.sumOf { it.kwh }
    var selected by remember(window) { mutableStateOf<Int?>(null) }
    val period = when (window.grain) {
        EnergyGrain.Daily -> "Last 7 days"
        EnergyGrain.Weekly -> "Last 7 weeks"
        EnergyGrain.Monthly -> "Last 6 months"
    }
    Text(period, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(kwh(total), fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text("kWh", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
    if (total <= 0.0) {
        EmptyState(icon = Icons.Outlined.Bolt, title = "No energy data yet", message = "The AC hasn't reported any usage for this period.")
        return
    }
    val sel = selected
    Text(
        if (sel != null) "${window.buckets[sel].label}: ${kwh(window.buckets[sel].kwh)} kWh" else "Tap a bar to see its value",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (sel != null) FontWeight.SemiBold else FontWeight.Normal,
        color = if (sel != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
    val barColor = Color(0xFF0A84FF)
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val max = window.buckets.maxOf { it.kwh }.coerceAtLeast(0.1)
    val n = window.buckets.size
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = "Energy chart, ${window.buckets.joinToString { "${it.label} ${kwh(it.kwh)} kWh" }}" }
            .pointerInput(window) {
                detectTapGestures { pos ->
                    val i = (pos.x / (size.width / n)).toInt().coerceIn(0, n - 1)
                    selected = if (selected == i) null else i
                }
            },
    ) {
        val slot = size.width / n
        val barW = slot * 0.56f
        window.buckets.forEachIndexed { i, b ->
            val h = (b.kwh / max).toFloat() * (size.height - 4f)
            val x = slot * i + (slot - barW) / 2
            drawRoundRect(dim, Offset(x, 0f), Size(barW, size.height), CornerRadius(6f, 6f))
            if (h > 0f) {
                val color = if (sel == null || sel == i) barColor else barColor.copy(alpha = 0.35f)
                drawRoundRect(color, Offset(x, size.height - h), Size(barW, h), CornerRadius(6f, 6f))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        window.buckets.forEach { b ->
            Text(
                b.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun kwh(v: Double): String = if (v >= 100) v.roundToInt().toString() else "%.1f".format(v)

// ---- sleep profiles ----------------------------------------------------------------------------------

private fun sleepContent(scope: LazyListScope, repo: AcRepository, ac: AcState, state: AcUiState) = with(scope) {
    val profiles = state.sleepProfiles[ac.device.deviceId]
    item { SleepHeader(repo, ac, profiles) }
    if (profiles != null) {
        items(profiles, key = { it.id ?: it.name }) { p -> SleepProfileCard(repo, ac, p, state.sleepRules) }
    }
}

@Composable
private fun SleepHeader(repo: AcRepository, ac: AcState, profiles: List<SleepProfile>?) {
    var load by remember { mutableStateOf<Load<Unit>?>(null) }
    var editing by remember { mutableStateOf<SleepProfile?>(null) }
    LaunchedEffect(ac.device.deviceId) {
        if (profiles == null) {
            load = Load.Loading
            load = runCatching { repo.loadSleepProfiles(ac.device.deviceId) }.fold({ Load.Done(Unit) }, { Load.Failed(it.message ?: "Couldn't load profiles") })
        }
    }
    val spec = remember(ac.modelJson) { SleepSpec.parse(ac.modelJson) ?: SleepSpec() }
    val customCount = profiles.orEmpty().count { !it.isDefault }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "A sleep profile changes the temperature every hour through the night, and can switch the AC off at the end.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ac.power && ac.mode == "fan") {
            Text("Sleep profiles can't run in Fan mode.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9500))
        }
        (load as? Load.Failed)?.let { ErrorBanner(it.message) }
        if (load == Load.Loading && profiles == null) {
            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        }
        if (profiles != null) {
            FilledTonalButton(
                onClick = { editing = SleepProfile(null, "", List(spec.minHours.coerceAtLeast(2)) { 26.0 }, offAtEnd = true) },
                enabled = customCount < SleepProfile.MAX_CUSTOM,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (customCount < SleepProfile.MAX_CUSTOM) "New profile" else "Up to ${SleepProfile.MAX_CUSTOM} custom profiles")
            }
        }
    }
    editing?.let { p -> SleepEditor(repo, ac, p, spec) { editing = null } }
}

@Composable
private fun SleepProfileCard(repo: AcRepository, ac: AcState, p: SleepProfile, rules: Map<String, SleepRule>) {
    val scope = rememberCoroutineScope()
    val active = p.isActive(ac.rawStatus[SleepProfile.KEY]?.toString())
    val spec = remember(ac.modelJson) { SleepSpec.parse(ac.modelJson) ?: SleepSpec() }
    var editing by remember { mutableStateOf(false) }
    var scheduling by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val rule = p.ruleIds.firstNotNullOfOrNull { rules[it] }
    val canStart = !(ac.power && ac.mode == "fan")

    HomeCard(tint = if (active) Color(0xFF5E5CE6) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bedtime, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(p.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${p.temps.size} h · ${p.temps.minOrNull()?.let { fmtT(it) } ?: "–"}–${p.temps.maxOrNull()?.let { fmtT(it) } ?: "–"}°" +
                        if (p.offAtEnd) " · turns AC off at end" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) StatusPill("Running", Color(0xFF5E5CE6))
        }
        Spacer(Modifier.height(8.dp))
        TempCurve(p.temps, spec)
        Spacer(Modifier.height(8.dp))
        rule?.let { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Starts at ${hm(r.schedule)} · ${days(r.schedule)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = r.enabled, onCheckedChange = { on ->
                    scope.launch { runCatching { repo.setScheduleEnabled(ac.device.deviceId, r.ruleId, on) }.onFailure { error = it.message } }
                })
            }
        }
        error?.let { ErrorBanner(it) }
        FlowRow2 {
            Button(onClick = { repo.setSleepProfileActive(ac.device.deviceId, p, !active) }, enabled = active || canStart) {
                Text(if (active) "Stop" else "Start")
            }
            OutlinedButton(onClick = { scheduling = true }) { Text(if (rule != null) "Schedule" else "Add schedule") }
            if (!p.isDefault) {
                OutlinedButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = { deleting = true }) { Text("Delete") }
            }
        }
    }
    if (editing) SleepEditor(repo, ac, p, spec) { editing = false }
    if (scheduling) ScheduleDialog(repo, ac, p, rule) { scheduling = false }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${p.displayName}”?") },
            text = { Text("The profile and its schedule are removed from your MirAIe account.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch { runCatching { repo.deleteSleepProfile(ac.device.deviceId, p) }.onFailure { error = it.message } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** Small line chart of the hourly temperatures. */
@Composable
private fun TempCurve(temps: List<Double>, spec: SleepSpec) {
    if (temps.isEmpty()) return
    val color = Color(0xFF5E5CE6)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "Hourly temperatures ${temps.joinToString { fmtT(it) }}" }) {
        // Scale to the profile's own spread (±2°) so hour-to-hour changes stay visible.
        val lo = maxOf(spec.minTemp, temps.min() - 2)
        val hi = minOf(spec.maxTemp, temps.max() + 2)
        val range = (hi - lo).coerceAtLeast(1.0)
        fun y(t: Double) = size.height - ((t - lo) / range).toFloat().coerceIn(0f, 1f) * (size.height - 8f) - 4f
        val step = if (temps.size > 1) size.width / (temps.size - 1) else 0f
        drawLine(grid, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
        val path = Path()
        temps.forEachIndexed { i, t -> if (i == 0) path.moveTo(0f, y(t)) else path.lineTo(step * i, y(t)) }
        drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
        temps.forEachIndexed { i, t -> drawCircle(color, 6f, Offset(step * i, y(t))) }
    }
}

@Composable
private fun SleepEditor(repo: AcRepository, ac: AcState, original: SleepProfile, spec: SleepSpec, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(original.name) }
    val temps = remember { original.temps.toMutableStateList() }
    var offAtEnd by remember { mutableStateOf(original.offAtEnd) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isNew = original.id == null

    AlertDialog(
        onDismissRequest = { if (!saving) onDone() },
        title = { Text(if (isNew) "New sleep profile" else "Edit ${original.displayName}") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Hour by hour (${spec.minHours}–${spec.maxHours} h, ${fmtT(spec.minTemp)}–${fmtT(spec.maxTemp)}°)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                temps.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hour ${i + 1}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        RoundIconButton(Icons.Filled.Remove, "Hour ${i + 1} colder", size = 32.dp, enabled = t > spec.minTemp) { temps[i] = t - 1 }
                        Text("${fmtT(t)}°", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(44.dp), textAlign = TextAlign.Center)
                        RoundIconButton(Icons.Filled.Add, "Hour ${i + 1} warmer", size = 32.dp, enabled = t < spec.maxTemp) { temps[i] = t + 1 }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { temps.add(temps.lastOrNull() ?: 26.0) }, enabled = temps.size < spec.maxHours) { Text("Add hour") }
                    OutlinedButton(onClick = { temps.removeAt(temps.lastIndex) }, enabled = temps.size > spec.minHours) { Text("Remove hour") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Turn AC off at the end", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = offAtEnd, onCheckedChange = { offAtEnd = it })
                }
                error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && name.isNotBlank() && !name.trim().equals(SleepProfile.DEFAULT_NAME, true),
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching { repo.saveSleepProfile(ac.device.deviceId, original.copy(name = name.trim(), temps = temps.toList(), offAtEnd = offAtEnd)) }
                            .onSuccess { onDone() }
                            .onFailure { error = it.message; saving = false }
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
private fun ScheduleDialog(repo: AcRepository, ac: AcState, p: SleepProfile, rule: SleepRule?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = rule?.schedule ?: Schedule(22, 30, (1..7).toSet())
    var hour by remember { mutableIntStateOf(initial.hour) }
    var minute by remember { mutableIntStateOf(initial.minute) }
    val days = remember { mutableStateListOf<Int>().apply { addAll(initial.days.sorted()) } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text("Schedule ${p.displayName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Start time", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.Filled.Remove, "Earlier", size = 36.dp) {
                        val t = (hour * 60 + minute - 15 + 1440) % 1440; hour = t / 60; minute = t % 60
                    }
                    Text(
                        "%02d:%02d".format(hour, minute),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    RoundIconButton(Icons.Filled.Add, "Later", size = 36.dp) {
                        val t = (hour * 60 + minute + 15) % 1440; hour = t / 60; minute = t % 60
                    }
                }
                Text("Repeat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    (1..7).forEach { d ->
                        OptionTile(Schedule.DAY_LABELS[d - 1].take(2), selected = d in days, modifier = Modifier.weight(1f)) {
                            if (d in days) days.remove(d) else days.add(d)
                        }
                    }
                }
                Text(
                    if (days.isEmpty()) "Runs once" else if (days.size == 7) "Runs every day" else "Runs on selected days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { ErrorBanner(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { repo.scheduleSleep(ac.device.deviceId, p, Schedule(hour, minute, days.toSet())) }
                        .onSuccess { onDone() }.onFailure { error = it.message; busy = false }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (rule != null) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            runCatching { repo.deleteSchedule(ac.device.deviceId, rule.ruleId) }
                                .onSuccess { onDone() }.onFailure { error = it.message; busy = false }
                        }
                    }) { Text("Remove") }
                }
                TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") }
            }
        },
    )
}

private fun hm(s: Schedule) = "%02d:%02d".format(s.hour, s.minute)

private fun days(s: Schedule): String = when {
    s.days.size == 7 -> "every day"
    s.days.isEmpty() -> "once"
    s.days == setOf(2, 3, 4, 5, 6) -> "weekdays"
    s.days == setOf(1, 7) -> "weekends"
    else -> s.days.sorted().joinToString(" ") { Schedule.DAY_LABELS[it - 1] }
}

private fun fmtT(t: Double): String = if (t % 1.0 == 0.0) t.toInt().toString() else "%.1f".format(t)

// ---- AC settings: name, room, filter, firmware ----------------------------------------------------------

private fun settingsContent(scope: LazyListScope, repo: AcRepository, ac: AcState, state: AcUiState) = with(scope) {
    item { NameRoomCard(repo, ac, state.cloudAvailable) }
    if (ac.spec("TimeBasedFilterHealth") != null || ac.spec("FilterCalibration") != null || ac.rawStatus.containsKey("filterDustLevel")) {
        item { FilterCard(repo, ac, state.cloudAvailable) }
    }
    item { FirmwareCard(repo, ac, state.cloudAvailable) }
}

@Composable
private fun SettingRow(label: String, value: String, cloud: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (cloud) {
                    Spacer(Modifier.width(6.dp))
                    CloudCaption()
                }
            }
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        action?.invoke()
    }
}

@Composable
private fun NameRoomCard(repo: AcRepository, ac: AcState, cloud: Boolean) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var spaces by remember { mutableStateOf<List<Space>?>(null) }
    var roomMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    HomeCard {
        SettingRow("Name", ac.device.name, cloud = true) {
            TextButton(onClick = { renaming = true }, enabled = cloud) { Text("Rename") }
        }
        SettingRow("Room", ac.device.space.ifEmpty { "—" }, cloud = true) {
            Box {
                TextButton(enabled = cloud, onClick = {
                    scope.launch {
                        runCatching { repo.spaces(ac.device.deviceId) }.onSuccess { spaces = it; roomMenu = true }.onFailure { error = it.message }
                    }
                }) { Text("Move") }
                DropdownMenu(expanded = roomMenu, onDismissRequest = { roomMenu = false }) {
                    spaces.orEmpty().forEach { sp ->
                        DropdownMenuItem(
                            text = { Text(sp.name + if (sp.id == ac.device.spaceId) " (current)" else "") },
                            enabled = sp.id != ac.device.spaceId,
                            onClick = {
                                roomMenu = false
                                scope.launch { runCatching { repo.moveDevice(ac.device.deviceId, sp) }.onFailure { error = it.message } }
                            },
                        )
                    }
                }
            }
        }
        if (!cloud) Text("Needs MirAIe sign-in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { ErrorBanner(it) }
    }
    if (renaming) {
        var name by remember { mutableStateOf(ac.device.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename AC") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    renaming = false
                    scope.launch { runCatching { repo.renameDevice(ac.device.deviceId, name) }.onFailure { error = it.message } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilterCard(repo: AcRepository, ac: AcState, cloud: Boolean) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val timeBased = ac.spec("TimeBasedFilterHealth") != null
    val hours = ac.rawStatus["totalOperatingHours"]?.toString()?.toDoubleOrNull()?.toInt()
    val thresholds = remember(ac.modelJson) { FilterHealth.thresholds(ac.modelJson) } ?: Triple(100, 200, 500)
    HomeCard {
        Text("Filter", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        if (timeBased && hours != null) {
            val health = FilterHealth(hours, thresholds.first, thresholds.second, thresholds.third)
            val (label, color) = when (health.level) {
                FilterHealth.Level.Good -> "Clean" to Color(0xFF34C759)
                FilterHealth.Level.Reminder -> "Clean soon" to Color(0xFFFFCC00)
                FilterHealth.Level.Warning -> "Needs cleaning" to Color(0xFFFF9500)
                FilterHealth.Level.Alert -> "Clean now" to Color(0xFFFF3B30)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$hours h since last clean", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                StatusPill(label, color)
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { (hours.toFloat() / thresholds.third).coerceIn(0f, 1f) },
                color = color,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
            Text(
                "Reminder at ${thresholds.first} h, warning at ${thresholds.second} h, alert at ${thresholds.third} h.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        ac.rawStatus["filterDustLevel"]?.toString()?.toDoubleOrNull()?.toInt()?.let {
            Text("Dust level $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { confirm = true }, enabled = !busy && (!timeBased || cloud)) {
                Text(if (busy) "Resetting…" else "Reset filter")
            }
            if (timeBased) {
                Spacer(Modifier.width(8.dp))
                CloudCaption()
            }
        }
        if (timeBased && !cloud) Text("Needs MirAIe sign-in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reset filter?") },
            text = { Text("Do this after cleaning the filter. The filter timer starts again from zero.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    busy = true
                    scope.launch {
                        result = runCatching { repo.resetFilter(ac.device.deviceId) }.getOrElse { it.message ?: "Reset failed" }
                        busy = false
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FirmwareCard(repo: AcRepository, ac: AcState, cloud: Boolean) {
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<Load<FirmwareInfo>?>(null) }
    HomeCard {
        SettingRow("Firmware", ac.rawStatus["V"]?.toString() ?: "Not reported yet", cloud = true) {
            TextButton(enabled = cloud && check != Load.Loading, onClick = {
                check = Load.Loading
                scope.launch {
                    check = runCatching { repo.checkFirmware(ac.device.deviceId) }.fold({ Load.Done(it) }, { Load.Failed(it.message ?: "Check failed") })
                }
            }) { Text("Check for update") }
        }
        when (val c = check) {
            Load.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            is Load.Failed -> ErrorBanner(c.message)
            is Load.Done -> Text(
                if (c.value.isLatest) "Up to date."
                else "Version ${c.value.latestVersion ?: "(newer)"} is available. Install it from the MirAIe app.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            null -> Text(
                "Updates are installed from the MirAIe app; this app only checks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!cloud) Text("Needs MirAIe sign-in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- notifications --------------------------------------------------------------------------------------

private val NOTIFICATION_TABS = listOf("alert" to "Alerts", "info" to "Info", "recommendation" to "Tips")

@Composable
private fun NotificationsContent(repo: AcRepository) {
    var tab by remember { mutableStateOf(NOTIFICATION_TABS.first().first) }
    val items = remember(tab) { mutableStateListOf<AcNotification>() }
    var page by remember(tab) { mutableIntStateOf(1) }
    var hasMore by remember(tab) { mutableStateOf(true) }
    var load by remember(tab) { mutableStateOf<Load<Unit>>(Load.Loading) }
    LaunchedEffect(tab, page) {
        load = Load.Loading
        load = runCatching { repo.notifications(tab, page) }.fold(
            { list -> items.addAll(list); hasMore = list.size >= 20; Load.Done(Unit) },
            { Load.Failed(it.message ?: "Couldn't load notifications") },
        )
        if (tab == "alert" && page == 1) runCatching { repo.refreshUnread() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NOTIFICATION_TABS.forEach { (key, label) ->
                OptionTile(label, selected = tab == key, modifier = Modifier.weight(1f)) { tab = key }
            }
        }
        (load as? Load.Failed)?.let { ErrorBanner(it.message) }
        if (load is Load.Done && items.isEmpty()) {
            EmptyState(icon = Icons.Outlined.Notifications, title = "Nothing here", message = "No ${NOTIFICATION_TABS.first { it.first == tab }.second.lowercase()} yet.")
        }
        items.forEach { n -> NotificationRow(n) }
        if (load == Load.Loading) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        } else if (hasMore && items.isNotEmpty()) {
            OutlinedButton(onClick = { page++ }, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
        }
    }
}

@Composable
private fun NotificationRow(n: AcNotification) {
    val color = when (n.severity) {
        "alert" -> Color(0xFFFF9500)
        "recommendation" -> Color(0xFF34C759)
        else -> Color(0xFF0A84FF)
    }
    HomeCard {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (n.body.isNotEmpty()) Text(n.body, style = MaterialTheme.typography.bodyMedium)
                Text(
                    listOfNotNull(n.deviceName, if (n.tsMillis > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(n.tsMillis)) else null)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Entry tile on the AC card for a sub-page. */
@Composable
fun NavTile(title: String, icon: ImageVector, detail: String?, cloud: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Metrics.tileRadius))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .pressable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                if (cloud) {
                    Spacer(Modifier.width(3.dp))
                    Icon(Icons.Outlined.Cloud, "Uses the MirAIe cloud", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                }
            }
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
