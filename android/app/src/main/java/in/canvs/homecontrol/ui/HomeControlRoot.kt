package `in`.canvs.homecontrol.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Cloud
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.canvs.homecontrol.miraie.AcRepository
import `in`.canvs.homecontrol.wiz.LightsRepository
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect

/** Routes on the back stack: an [AcPage] encoding, or "bulb:<mac>". Empty stack = home (tabs). */
private fun isBulbRoute(r: String) = r.startsWith("bulb:")

@Composable
fun HomeControlRoot(ac: AcRepository, lights: LightsRepository) {
    HomeControlTheme {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
        var forward by remember { mutableStateOf(true) }
        var addRoomRequest by remember { mutableIntStateOf(0) }
        val climateList = rememberLazyListState()
        val lightsList = rememberLazyListState()
        val density = LocalDensity.current.density

        fun push(route: String) { forward = true; stack = stack + route }
        fun pop() { if (stack.isNotEmpty()) { forward = false; stack = stack.dropLast(1) } }
        BackHandler(enabled = stack.isNotEmpty()) { pop() }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            AnimatedContent(
                targetState = stack.lastOrNull(),
                transitionSpec = { pageTransition(forward, density) },
                label = "page",
            ) { route ->
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    when {
                        route == null -> Column(Modifier.fillMaxSize()) {
                            Header(
                                ac, lights,
                                onOpenAcPage = { push(it.encode()) },
                                onAddRoom = { tab = 1; addRoomRequest++ },
                            )
                            TabSwitcher(tab) { tab = it }
                            Spacer(Modifier.height(Metrics.sectionSpacing))
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> AcScreen(ac, climateList, onOpen = { push(it.encode()) })
                                    else -> LightsScreen(lights, lightsList, addRoomRequest, onOpenBulb = { push("bulb:$it") })
                                }
                            }
                        }
                        isBulbRoute(route) -> BulbPage(lights, route.removePrefix("bulb:"), onBack = ::pop)
                        else -> AcPage.decode(route)?.let { page ->
                            AcPageHost(page, ac, onBack = ::pop, onOpen = { push(it.encode()) })
                        } ?: LaunchedEffect(route) { pop() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(
    ac: AcRepository,
    lights: LightsRepository,
    onOpenAcPage: (AcPage) -> Unit,
    onAddRoom: () -> Unit,
) {
    val acState by ac.state.collectAsStateWithLifecycle()
    val lightState by lights.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    val summary = buildList {
        if (acState.loggedIn) {
            val on = acState.devices.count { it.power }
            add(if (on == 0) "AC off" else if (on == 1) "1 AC on" else "$on ACs on")
        }
        if (lightState.bulbs.isNotEmpty()) {
            add("${lightState.bulbs.count { it.on }} of ${lightState.bulbs.size} lights on")
        }
    }.joinToString(" · ").ifEmpty { "Nothing connected yet" }

    Row(
        Modifier.fillMaxWidth().padding(start = Metrics.screenPadding, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Home", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            RoundIconButton(Icons.Outlined.Settings, "Settings", size = 40.dp) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (acState.loggedIn) {
                    CheckItem("AC: Local network only", acState.localOnly) { ac.setLocalOnly(!acState.localOnly) }
                    DropdownMenuItem(
                        text = { Text(if (acState.unreadAlerts > 0) "AC notifications (${acState.unreadAlerts})" else "AC notifications") },
                        trailingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = "Uses the MirAIe cloud", modifier = Modifier.size(16.dp)) },
                        onClick = { menuOpen = false; onOpenAcPage(AcPage.Notifications) },
                    )
                    acState.devices.firstOrNull()?.let { first ->
                        DropdownMenuItem(
                            text = { Text("AC keys and diagnostics") },
                            onClick = { menuOpen = false; onOpenAcPage(AcPage.More(first.device.deviceId)) },
                        )
                    }
                    CheckItem("Try local control (experimental)", acState.experimentalLocal) {
                        ac.setExperimentalLocal(!acState.experimentalLocal)
                    }
                    DropdownMenuItem(
                        text = { Text("Copy AC key code") },
                        enabled = ac.exportable.isNotEmpty(),
                        onClick = {
                            ac.exportKeyCode()?.let { copySecret(context, it) }
                            menuOpen = false
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Sign out of MirAIe") }, onClick = { ac.logout(); menuOpen = false })
                    HorizontalDivider()
                }
                DropdownMenuItem(text = { Text("Add light room") }, onClick = { menuOpen = false; onAddRoom() })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Close app") },
                    onClick = { menuOpen = false; (context as? Activity)?.finishAndRemoveTask() },
                )
            }
        }
    }
}

@Composable
private fun CheckItem(title: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title) },
        onClick = onClick,
        trailingIcon = { if (checked) Icon(Icons.Filled.Check, contentDescription = "On") else Spacer(Modifier.size(24.dp)) },
    )
}

/** Copies a secret to the clipboard, flagged sensitive so Android 13+ hides it in the clipboard preview. */
fun copySecret(context: Context, text: String, label: String = "AC key code", toast: String = "Key code copied") {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    cm.setPrimaryClip(clip)
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

private data class TabInfo(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    TabInfo("Climate", Icons.Outlined.AcUnit, Icons.Filled.AcUnit),
    TabInfo("Lights", Icons.Outlined.Lightbulb, Icons.Filled.Lightbulb),
)

/** Pill segmented control. Tab switches are frequent, so selection changes instantly. */
@Composable
private fun TabSwitcher(selected: Int, onSelect: (Int) -> Unit) {
    val trackShape = RoundedCornerShape(12.dp) // = inner 9 dp + 3 dp padding
    Row(
        Modifier
            .padding(horizontal = Metrics.screenPadding)
            .fillMaxWidth()
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TABS.forEachIndexed { i, t ->
            val isSel = i == selected
            val shape = RoundedCornerShape(9.dp)
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .then(if (isSel) Modifier.shadow(1.dp, shape, ambientColor = Color.Black.copy(0.1f)) else Modifier)
                    .clip(shape)
                    .background(
                        when {
                            !isSel -> Color.Transparent
                            isLightTheme() -> MaterialTheme.colorScheme.surfaceContainerLowest
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    )
                    .pressable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = isSel },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isSel) t.selectedIcon else t.icon,
                    contentDescription = null,
                    tint = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    t.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
