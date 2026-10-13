package `in`.canvs.homecontrol.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Surface metrics. Concentric radii: card radius = tile radius + card padding. */
object Metrics {
    val cardPadding = 12.dp
    val tileRadius = 6.dp
    val cardRadius = 18.dp // tileRadius + cardPadding
    val sectionSpacing = 12.dp
    val screenPadding = 16.dp
}

@Composable
fun HomeControlTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** True when the current theme is light (used for contrast tweaks on pastel fills). */
@Composable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() > 0.5f

/** Press feedback: scale to 0.96 over 150 ms ease-out. Disabled controls never scale. */
@Composable
private fun Modifier.composedPressScale(source: MutableInteractionSource, enabled: Boolean): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (enabled && pressed) 0.96f else 1f, tween(150), label = "press")
    return this.scale(scale)
}

/** Clickable with press-scale and no ripple duplication (the scale is the pressed state). */
@Composable
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .composedPressScale(source, enabled)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** Elevated card: tonal fill + soft shadow, optional colour wash. */
@Composable
fun HomeCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val fill = tint?.copy(alpha = 0.10f)?.compositeOver(base) ?: base
    Column(
        modifier
            .fillMaxWidth()
            .shadow(3.dp, shape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.12f))
            .background(fill, shape)
            .padding(Metrics.cardPadding),
        content = content,
    )
}

private val neutralFill @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

/** Selectable tile for AC mode / fan speed. Selection is instant (frequent interaction). */
@Composable
fun OptionTile(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    val fill = when {
        !selected -> neutralFill
        muted -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
        else -> tint
    }
    val fg = when {
        selected && !muted -> Color.White
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }
    Column(
        modifier
            .heightIn(min = if (icon != null) 56.dp else 40.dp)
            .clip(RoundedCornerShape(Metrics.tileRadius))
            .background(fill)
            .pressable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(3.dp))
        }
        Text(
            title,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Circular icon button (steppers, refresh, settings). 48 dp touch target. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(maxOf(size, 48.dp))
            .pressable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** Large circular power toggle; fill and icon colour change together (state never relies on motion). */
@Composable
fun PowerButton(isOn: Boolean, label: String, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .pressable(role = Role.Switch, onClick = onClick)
            .semantics { contentDescription = label; stateDescription = if (isOn) "On" else "Off" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .background(if (isOn) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = if (isOn) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Small status capsule. Text is darkened in light theme so pastel tints keep contrast. */
@Composable
fun StatusPill(text: String, color: Color, icon: ImageVector? = null) {
    val fg = if (isLightTheme()) lerp(color, Color.Black, 0.4f) else color
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionHeader(title: String, detail: String?, busy: Boolean, refreshLabel: String, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        if (busy) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            RoundIconButton(Icons.Filled.Refresh, refreshLabel, size = 34.dp, onClick = onRefresh)
        }
    }
}

@Composable
fun ErrorBanner(message: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, actionTitle: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionTitle != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onAction, modifier = Modifier.defaultMinSize(minHeight = 40.dp)) { Text(actionTitle) }
        }
    }
}

/** Expand/collapse: enter 300 ms with a 12 dp rise, exit 150 ms. System animation scale 0 makes both instant. */
fun revealEnter(density: Float) = fadeIn(tween(300)) + slideInVertically(tween(300)) { (12 * density).toInt() }
fun revealExit(density: Float) = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -(12 * density).toInt() }
