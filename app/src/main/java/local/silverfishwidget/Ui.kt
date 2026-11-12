package local.silverfishwidget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * The parts the settings screen is built from, as in Obsidian Widget (after Paid to Poop's and
 * PurgeBot's): a plain page header, cards per topic with an icon tile, sliding pill pickers and
 * stepped sliders. Colours are the system's dynamic ones.
 */

/**
 * The settings' colours: Catppuccin, as the Obsidian vault (and so Obsidian Widget's settings)
 * wears it. Macchiato in dark mode, Latte in light: page, cards a step darker, mauve accent.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val page: Color; val card: Color; val text: Color; val muted: Color; val accent: Color; val onAccent: Color; val faint: Color
    if (dark) {
        page = Color(0xFF24273A); card = Color(0xFF1E2030); text = Color(0xFFCAD3F5); muted = Color(0xFF848AA4)
        accent = Color(0xFFC6A0F6); onAccent = Color(0xFF24273A); faint = Color(0xFF494D64)
    } else {
        page = Color(0xFFEFF1F5); card = Color(0xFFE6E9EF); text = Color(0xFF4C4F69); muted = Color(0xFF6C6F85)
        accent = Color(0xFF8839EF); onAccent = Color(0xFFEFF1F5); faint = Color(0xFFBCC0CC)
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = accent, onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.2f), onPrimaryContainer = text,
        secondary = accent, onSecondary = onAccent,
        background = page, onBackground = text,
        surface = page, onSurface = text,
        surfaceVariant = card, onSurfaceVariant = muted,
        surfaceContainerLowest = page, surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = card, surfaceContainerHighest = card,
        outline = faint, outlineVariant = faint.copy(alpha = 0.5f),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** A light tick, as Paid to Poop gives every tap. */
@Composable
fun rememberHaptic(): () -> Unit {
    val ctx = LocalContext.current
    return { Haptics.tick(ctx) }
}

/** Description text under a setting: softer than its label, so it never reads as a heading. */
@Composable
fun noteColor() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)

/** A page's header: the title on the page's own background, with a back arrow before it. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    val tick = rememberHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp),
    ) {
        IconButton(onClick = { tick(); onBack() }) {
            Icon(painterResource(R.drawable.ic_back), "Back", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A settings card: an icon in a small tile and a title, then its settings. */
@Composable
fun SectionCard(title: String, icon: Int, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                IconTile(icon)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

/** A Lucide icon on a small rounded tile of the page colour. */
@Composable
fun IconTile(icon: Int, size: Dp = 32.dp, tint: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.56f))
    }
}

/** A setting's label and description, above its control, with its current [value] at the right. */
@Composable
fun SettingLabel(title: String, description: String? = null, value: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = noteColor()) }
        }
        value?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
    }
}

/** A slider that snaps to every [step] in [range], with tick marks and a light tick on each step. */
@Composable
fun StepSlider(value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    val tick = rememberHaptic()
    Slider(
        value = value.toFloat(),
        onValueChange = { v ->
            val snapped = ((v / step).roundToInt() * step).coerceIn(range.first, range.last)
            if (snapped != value) {
                tick()
                onChange(snapped)
            }
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first) / step - 1,
        colors = SliderDefaults.colors(
            inactiveTrackColor = MaterialTheme.colorScheme.background,
            activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
            inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    )
}

/** A row of options on a rounded track, the picked one on a sliding accent pill. For a few short choices. */
@Composable
fun <T> PillSegmented(options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    val tick = rememberHaptic()
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .padding(4.dp),
    ) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * index, label = "pill")
        Box(
            Modifier
                .offset(x = offset)
                .width(segment)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { option ->
                val on = option == selected
                val color by animateColorAsState(
                    if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "pillText",
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .selectable(selected = on, role = Role.RadioButton) {
                            if (!on) tick()
                            onSelect(option)
                        },
                ) {
                    Text(
                        name(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** A row that opens something: an icon tile, its label and value, then [trailing] (a chevron by default). */
@Composable
fun NavRow(
    title: String, subtitle: String?, icon: Int, onClick: () -> Unit,
    subtitleIcon: Int? = null, trailing: (@Composable () -> Unit)? = null,
) {
    val tick = rememberHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { tick(); onClick() }
            .padding(vertical = 8.dp),
    ) {
        IconTile(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = noteColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitleIcon?.let { ic ->
                        Icon(painterResource(ic), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                    }
                }
            }
        }
        trailing?.invoke() ?: Icon(
            painterResource(R.drawable.ic_chevron_right), null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
        )
    }
}

/** A setting that's on or off: the whole row toggles, the switch shows which. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val tick = rememberHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch) { tick(); onChange(it) }
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = noteColor()) }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
