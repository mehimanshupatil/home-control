package `in`.canvs.homecontrol.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LightbulbCircle
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.canvs.homecontrol.wiz.Bulb
import `in`.canvs.homecontrol.wiz.LightsRepository
import `in`.canvs.homecontrol.wiz.WizClient
import `in`.canvs.homecontrol.wiz.LightRoom
import `in`.canvs.homecontrol.wiz.LightPreset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn

private val SWATCHES = listOf(
    Triple(255, 59, 48), Triple(255, 149, 0), Triple(255, 214, 10), Triple(52, 199, 89),
    Triple(0, 199, 190), Triple(10, 132, 255), Triple(175, 82, 222), Triple(255, 45, 85),
)

@Composable
fun LightsScreen(
    repo: LightsRepository,
    listState: LazyListState = rememberLazyListState(),
    addRoomRequest: Int = 0,
    onOpenBulb: (String) -> Unit = {},
) {
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { if (state.bulbs.isEmpty()) repo.discover() }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = Metrics.screenPadding, end = Metrics.screenPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing),
    ) {
        item {
            SectionHeader(
                title = "Lights",
                detail = if (state.bulbs.isEmpty()) null else "${state.bulbs.count { it.on }} of ${state.bulbs.size} on",
                busy = state.scanning,
                refreshLabel = "Scan for WiZ bulbs",
            ) { scope.launch { repo.discover() } }
        }
        if (state.bulbs.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction("All on", Icons.Filled.Lightbulb, Color(0xFFFFCC00), Modifier.weight(1f)) { repo.setAll(true) }
                    QuickAction("All off", Icons.Outlined.Lightbulb, null, Modifier.weight(1f)) { repo.setAll(false) }
                }
            }
        }
        if (state.bulbs.isEmpty() && !state.scanning) {
            item {
                EmptyState(
                    icon = Icons.Outlined.LightbulbCircle,
                    title = "No bulbs found",
                    message = "Make sure this phone is on the same Wi-Fi as your WiZ bulbs.",
                    actionTitle = "Scan again",
                ) { scope.launch { repo.discover() } }
            }
        }
        if (state.presets.isNotEmpty()) item(key = "presets") { PresetsRow(state.presets, repo) }
        val roomed = state.rooms.flatMap { it.macs }.toSet()
        state.rooms.forEach { room ->
            val bulbs = state.bulbs.filter { it.mac in room.macs }
            item(key = "room-${room.id}") { RoomCard(room, bulbs, repo) }
            bulbs.chunked(2).forEachIndexed { i, pair ->
                item(key = "room-${room.id}-row-$i") { TileRow(pair, repo, onOpenBulb) }
            }
        }
        val others = state.bulbs.filter { it.mac !in roomed }
        if (others.isNotEmpty()) {
            if (state.rooms.isNotEmpty()) {
                item(key = "others") { SectionLabel("Other lights") }
            }
            others.chunked(2).forEachIndexed { i, pair ->
                item(key = "others-row-$i") { TileRow(pair, repo, onOpenBulb) }
            }
        }
        item(key = "add-room") { AddRoomButton(repo, addRoomRequest, visible = state.bulbs.isNotEmpty()) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
}

/** Two bulb tiles side by side; a lone tile keeps half width. */
@Composable
private fun TileRow(pair: List<Bulb>, repo: LightsRepository, onOpen: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing)) {
        pair.forEach { b -> BulbTile(b, repo, Modifier.weight(1f)) { onOpen(b.mac) } }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}

/**
 * Bulb tile: glowing icon (tap toggles) + name + status. Tap the tile body to open the bulb page.
 * The tile is tinted with the bulb's colour when on.
 */
@Composable
private fun BulbTile(bulb: Bulb, repo: LightsRepository, modifier: Modifier, onOpen: () -> Unit) {
    val light = lightColor(bulb)
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val fill = if (bulb.on) light.copy(alpha = 0.16f).compositeOver(base) else base
    Column(
        modifier
            .shadow(3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(shape)
            .background(fill)
            .pressable(onClick = onOpen)
            .semantics { contentDescription = "${bulb.name}, ${statusText(bulb)}. Opens light settings" }
            .padding(Metrics.cardPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .pressable(role = Role.Switch) { repo.setOn(bulb.mac, !bulb.on) }
                    .semantics { contentDescription = "${bulb.name} power"; stateDescription = if (bulb.on) "On" else "Off" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .then(if (bulb.on) Modifier.shadow(8.dp, CircleShape, ambientColor = light, spotColor = light) else Modifier)
                        .clip(CircleShape)
                        .background(if (bulb.on) light else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CrossfadeIcon(
                        if (bulb.on) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                        tint = if (bulb.on) Color.Black.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(bulb.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            statusText(bulb),
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Bulb page: brightness, white, colour, scene + speed, presets, identify, rename, room. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BulbPage(repo: LightsRepository, mac: String, onBack: () -> Unit) {
    val state by repo.state.collectAsStateWithLifecycle()
    val bulb = state.bulbs.firstOrNull { it.mac == mac }
    var renaming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageBar(bulb?.name ?: "Light", bulb?.let { statusText(it) }, onBack) {
            if (bulb != null) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    PowerButton(bulb.on, label = "${bulb.name} power", tint = Color(0xFFFF9F0A)) { repo.setOn(bulb.mac, !bulb.on) }
                }
            }
        }
        if (bulb == null) {
            EmptyState(icon = Icons.Outlined.LightbulbCircle, title = "Light not found", message = "Go back and scan again.")
            return@Column
        }
        var dimming by remember(bulb.mac, bulb.dimming) { mutableFloatStateOf(bulb.dimming.toFloat()) }
        var kelvin by remember(bulb.mac, bulb.temp) { mutableFloatStateOf((bulb.temp ?: 4000).toFloat()) }
        var hue by remember(bulb.mac) { mutableFloatStateOf(0f) }
        var speed by remember(bulb.mac, bulb.speed) { mutableFloatStateOf((bulb.speed ?: 100).toFloat()) }
        val light = lightColor(bulb)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Metrics.screenPadding, end = Metrics.screenPadding, bottom = 24.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing),
        ) {
            item {
                HomeCard(tint = if (bulb.on) light else null) {
                    CardTitle("Brightness") {
                        Text("${dimming.roundToInt()}%", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
                    }
                    Slider(
                        value = dimming,
                        onValueChange = { dimming = it },
                        onValueChangeFinished = { repo.setBrightness(bulb.mac, dimming.roundToInt()) },
                        valueRange = 10f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = light,
                            activeTrackColor = light,
                            inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                            inactiveTickColor = Color.Transparent,
                            activeTickColor = Color.Transparent,
                        ),
                        modifier = Modifier.semantics { contentDescription = "Brightness" },
                    )
                    if (!bulb.on) Text("Turning brightness up also turns the light on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                HomeCard {
                    CardTitle("White")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Warm", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = kelvin,
                            onValueChange = { kelvin = it },
                            onValueChangeFinished = { repo.setTemperature(bulb.mac, kelvin.roundToInt()) },
                            valueRange = 2200f..6500f,
                            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface),
                            track = { GradientTrack(WHITES) },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Color temperature" },
                        )
                        Text("Cool", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                HomeCard {
                    CardTitle("Colour")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SWATCHES.forEach { rgb -> Swatch(rgb, selected = bulb.rgb == rgb) { repo.setColor(bulb.mac, rgb.first, rgb.second, rgb.third) } }
                    }
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = hue,
                        onValueChange = { hue = it },
                        onValueChangeFinished = {
                            val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
                            repo.setColor(bulb.mac, android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
                        },
                        valueRange = 0f..360f,
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.onSurface),
                        track = { GradientTrack(HUES) },
                        modifier = Modifier.semantics { contentDescription = "Custom color hue" },
                    )
                }
            }
            item {
                HomeCard {
                    CardTitle("Scene") { SceneMenu(bulb.sceneId) { repo.setScene(bulb.mac, it) } }
                    if (bulb.on && bulb.sceneId > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Slow", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(
                                value = speed,
                                onValueChange = { speed = it },
                                onValueChangeFinished = { repo.setSpeed(bulb.mac, speed.roundToInt()) },
                                valueRange = 10f..200f,
                                colors = SliderDefaults.colors(
                                    thumbColor = light,
                                    activeTrackColor = light,
                                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                    inactiveTickColor = Color.Transparent,
                                    activeTickColor = Color.Transparent,
                                ),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Scene speed" },
                            )
                            Text("Fast", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text("Pick a scene to see its speed control.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                HomeCard {
                    CardTitle("Light")
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Name", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { renaming = true }) { Text("Rename") }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Room", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (state.rooms.isEmpty()) {
                            Text("Add a room first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            RoomMenu(state.rooms, state.rooms.firstOrNull { bulb.mac in it.macs }) { repo.assignBulb(bulb.mac, it?.id) }
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Find this light", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { repo.identify(bulb.mac) }) { Text("Identify") }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Preset", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        SavePresetButton(repo, listOf(bulb.mac), compact = true)
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("IP address", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(bulb.ip, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (renaming) {
            var name by remember(bulb.mac) { mutableStateOf(bulb.name) }
            AlertDialog(
                onDismissRequest = { renaming = false },
                title = { Text("Rename light") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
                confirmButton = {
                    TextButton(onClick = { repo.rename(bulb.mac, name.trim().ifEmpty { bulb.name }); renaming = false }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun QuickAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color?, modifier: Modifier, onClick: () -> Unit) {
    val fill = (tint ?: MaterialTheme.colorScheme.onSurface).copy(alpha = if (tint != null) 0.16f else 0.07f)
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .pressable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    }
}

private val HUES = (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) }
private val WHITES = listOf(Color(0xFFFFB86B), Color.White, Color(0xFFC7E0FF))

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun GradientTrack(colors: List<Color>) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(colors))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape),
    )
}

@Composable
private fun Swatch(rgb: Triple<Int, Int, Int>, selected: Boolean, onClick: () -> Unit) {
    val fill = Color(rgb.first, rgb.second, rgb.third)
    Box(
        Modifier
            .size(40.dp)
            .pressable(onClick = onClick)
            .semantics { contentDescription = "Color ${rgb.first}, ${rgb.second}, ${rgb.third}"; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f) else Color.Transparent, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), CircleShape),
        )
    }
}

@Composable
private fun SceneMenu(sceneId: Int, placeholder: String = "None", onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = WizClient.SCENES.firstOrNull { it.first == sceneId }?.second ?: placeholder
    Box {
        Row(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                .pressable { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                enabled = false,
                onClick = {},
                trailingIcon = { if (sceneId == 0) Icon(Icons.Filled.Check, contentDescription = "Selected") },
            )
            WizClient.SCENE_GROUPS.forEach { (group, ids) ->
                HorizontalDivider()
                Text(
                    group,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
                )
                ids.forEach { id ->
                    val name = WizClient.sceneName(id) ?: return@forEach
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = { onSelect(id); open = false },
                        trailingIcon = { if (id == sceneId) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    )
                }
            }
        }
    }
}

private fun lightColor(bulb: Bulb): Color {
    bulb.rgb?.let { (r, g, b) -> return Color(r, g, b) }
    // Approximate white temperature: warm amber → neutral → cool blue-white.
    val t = (((bulb.temp ?: 3500) - 2200f) / (6500f - 2200f)).coerceIn(0f, 1f)
    return Color(1f, 0.78f + 0.2f * t, 0.45f + 0.55f * t)
}

private fun statusText(bulb: Bulb): String {
    if (!bulb.on) return "Off"
    val scene = WizClient.SCENES.firstOrNull { it.first == bulb.sceneId }?.second
    val detail = when {
        bulb.sceneId > 0 && scene != null -> scene
        bulb.rgb != null -> "Color"
        bulb.temp != null -> "${bulb.temp}K"
        else -> null
    }
    return listOfNotNull("${bulb.dimming}%", detail).joinToString(" · ")
}

// ---- rooms and presets (local, this phone only) ---------------------------------------------------------

@Composable
private fun RoomCard(room: LightRoom, bulbs: List<Bulb>, repo: LightsRepository) {
    val onCount = bulbs.count { it.on }
    val anyOn = onCount > 0
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var savingPreset by remember { mutableStateOf(false) }
    val avg = bulbs.filter { it.on }.map { it.dimming }.average().takeIf { !it.isNaN() } ?: 100.0
    var dimming by remember(room.id, avg) { mutableFloatStateOf(avg.toFloat()) }
    val tint = Color(0xFFFFCC00)
    val accent = Color(0xFFFF9F0A)

    HomeCard(tint = if (anyOn) tint else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.semantics { heading() })
                Text(
                    if (bulbs.isEmpty()) "No lights yet. Assign them from a light's page." else "$onCount of ${bulbs.size} on",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (bulbs.isNotEmpty()) {
                val common = bulbs.map { it.sceneId }.distinct().singleOrNull() ?: 0
                SceneMenu(common, placeholder = "Scene") { repo.setRoomScene(room.id, it) }
            }
            Box {
                RoundIconButton(Icons.Filled.MoreVert, "Room options", size = 36.dp) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (bulbs.isNotEmpty()) DropdownMenuItem(text = { Text("Save as preset") }, onClick = { menu = false; savingPreset = true })
                    DropdownMenuItem(text = { Text("Rename room") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Delete room") }, onClick = { menu = false; deleting = true })
                }
            }
            if (bulbs.isNotEmpty()) PowerButton(anyOn, label = "${room.name} lights", tint = accent) { repo.setRoomOn(room.id, !anyOn) }
        }
        if (bulbs.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WbSunny, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Slider(
                    value = dimming,
                    onValueChange = { dimming = it },
                    onValueChangeFinished = { repo.setRoomBrightness(room.id, dimming.roundToInt()) },
                    valueRange = 10f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor = accent,
                        activeTrackColor = accent,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                        inactiveTickColor = Color.Transparent,
                        activeTickColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "${room.name} brightness" },
                )
                Text(
                    "${dimming.roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
            }
        }
    }
    if (savingPreset) PresetNameDialog(bulbs.size, onDismiss = { savingPreset = false }) { repo.savePreset(it, bulbs.map { b -> b.mac }) }
    if (renaming) {
        var name by remember { mutableStateOf(room.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename room") },
            text = { OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { repo.renameRoom(room.id, name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${room.name}”?") },
            text = { Text("The lights stay; they just move to Other lights. Rooms are stored on this phone only.") },
            confirmButton = { TextButton(onClick = { repo.deleteRoom(room.id); deleting = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddRoomButton(repo: LightsRepository, request: Int, visible: Boolean) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    LaunchedEffect(request) { if (request > 0) { name = ""; naming = true } }
    if (visible) {
        OutlinedButton(onClick = { name = ""; naming = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add room")
        }
    }
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("New room") },
            text = {
                Column {
                    Text("Rooms are stored on this phone only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("Name, e.g. Bedroom") })
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { repo.createRoom(name); naming = false }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RoomMenu(rooms: List<LightRoom>, current: LightRoom?, onSelect: (LightRoom?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                .pressable { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current?.name ?: "None", style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("None") },
                onClick = { onSelect(null); open = false },
                trailingIcon = { if (current == null) Icon(Icons.Filled.Check, contentDescription = "Selected") },
            )
            rooms.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.name) },
                    onClick = { onSelect(r); open = false },
                    trailingIcon = { if (current?.id == r.id) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                )
            }
        }
    }
}

@Composable
private fun SavePresetButton(repo: LightsRepository, macs: List<String>, compact: Boolean) {
    var naming by remember { mutableStateOf(false) }
    TextButton(onClick = { naming = true }) {
        if (!compact) {
            Icon(Icons.Outlined.Bookmark, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(if (compact) "Save preset" else "Save as preset")
    }
    if (naming) PresetNameDialog(macs.size, onDismiss = { naming = false }) { repo.savePreset(it, macs) }
}

@Composable
private fun PresetNameDialog(count: Int, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset") },
        text = {
            Column {
                Text(
                    "Saves the current on/off, brightness, colour or scene of ${if (count == 1) "this light" else "these $count lights"}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("Name, e.g. Movie") })
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Saved presets: tap to apply, long-press to delete. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun PresetsRow(presets: List<LightPreset>, repo: LightsRepository) {
    val haptics = LocalHapticFeedback.current
    var deleting by remember { mutableStateOf<LightPreset?>(null) }
    Column {
        Label("Presets")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p ->
                Row(
                    Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                        .combinedClickable(
                            onClick = { repo.applyPreset(p) },
                            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); deleting = p },
                            onLongClickLabel = "Delete preset",
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Bookmark, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(p.name, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("This preset is removed from this phone only.") },
            confirmButton = { TextButton(onClick = { repo.deletePreset(p.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
