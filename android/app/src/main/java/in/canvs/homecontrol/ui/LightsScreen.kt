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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SWATCHES = listOf(
    Triple(255, 59, 48), Triple(255, 149, 0), Triple(255, 214, 10), Triple(52, 199, 89),
    Triple(0, 199, 190), Triple(10, 132, 255), Triple(175, 82, 222), Triple(255, 45, 85),
)

@Composable
fun LightsScreen(repo: LightsRepository) {
    val state by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Bulb?>(null) }

    LaunchedEffect(Unit) { if (state.bulbs.isEmpty()) repo.discover() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Metrics.screenPadding, end = Metrics.screenPadding, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.sectionSpacing),
    ) {
        item {
            SectionHeader(
                title = "Lights",
                detail = if (state.bulbs.isEmpty()) null else "${state.bulbs.size} found",
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
        items(state.bulbs, key = { it.mac }) { bulb -> BulbCard(bulb, repo) { renaming = bulb } }
    }

    renaming?.let { bulb ->
        var name by remember(bulb.mac) { mutableStateOf(bulb.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename bulb") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = {
                TextButton(onClick = { repo.rename(bulb.mac, name.trim().ifEmpty { bulb.name }); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BulbCard(bulb: Bulb, repo: LightsRepository, onRename: () -> Unit) {
    val density = LocalDensity.current.density
    var expanded by rememberSaveable(bulb.mac) { mutableStateOf(false) }
    var dimming by remember(bulb.mac, bulb.dimming) { mutableFloatStateOf(bulb.dimming.toFloat()) }
    var kelvin by remember(bulb.mac, bulb.temp) { mutableFloatStateOf((bulb.temp ?: 4000).toFloat()) }
    var hue by remember(bulb.mac) { mutableFloatStateOf(0f) }
    var speed by remember(bulb.mac, bulb.speed) { mutableFloatStateOf((bulb.speed ?: 100).toFloat()) }
    val light = lightColor(bulb)

    HomeCard(tint = if (bulb.on) light else null) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
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
                    Icon(
                        if (bulb.on) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                        null,
                        tint = if (bulb.on) Color.Black.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(bulb.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(statusText(bulb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box(
                Modifier
                    .size(48.dp)
                    .pressable { expanded = !expanded }
                    .semantics { contentDescription = if (expanded) "Hide options" else "Show options" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (expanded) 0.1f else 0.05f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, null, modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f))
                }
            }
        }

        // Brightness (only meaningful when on)
        AnimatedVisibility(bulb.on, enter = revealEnter(density), exit = revealExit(density)) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WbSunny, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
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
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "Brightness" },
                )
                Text(
                    "${dimming.roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
            }
        }

        // Details
        AnimatedVisibility(expanded, enter = revealEnter(density), exit = revealExit(density)) {
            Column(Modifier.padding(top = 8.dp)) {
                Label("White")
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

                Spacer(Modifier.height(8.dp))
                Label("Color")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SWATCHES.forEach { rgb -> Swatch(rgb, selected = bulb.rgb == rgb) { repo.setColor(bulb.mac, rgb.first, rgb.second, rgb.third) } }
                }
                Spacer(Modifier.height(4.dp))
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Scene", Modifier.weight(1f))
                    SceneMenu(bulb.sceneId) { repo.setScene(bulb.mac, it) }
                }
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
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bulb.ip, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                    TextButton(onClick = { repo.identify(bulb.mac) }) { Text("Identify") }
                    TextButton(onClick = onRename) { Text("Rename") }
                }
            }
        }
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
private fun SceneMenu(sceneId: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = WizClient.SCENES.firstOrNull { it.first == sceneId }?.second ?: "None"
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
