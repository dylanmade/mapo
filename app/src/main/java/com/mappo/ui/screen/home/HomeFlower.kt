package com.mappo.ui.screen.home

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mappo.ui.screen.softDropShadow
import kotlinx.coroutines.delay

private const val TAG = "HomeFlower"

// Deliberate fixed retro accents (user-specified): the four home directions keep a stable
// identity color across theme-seed re-tints. Decorative/identity use only — text stays on
// theme roles for contrast.
internal val ProfileAccent = Color(0xFF4C9DFF) // up — profile
internal val OverlayAccent = Color(0xFF41C36D) // left — overlay
internal val RemapAccent = Color(0xFFAB7DFF) // right — remapping
internal val SettingsAccent = Color(0xFFF2A93B) // down — settings

enum class FlowerDirection { Up, Left, Right, Down }

/** One entry of the (temporary) "Settings" fly-out holding the misc destinations. */
data class HomeMenuEntry(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * The Mappo home: a "d-pad flower". A d-pad glyph in the center of the handheld's screen with
 * four option petals in the cardinal directions. A physical d-pad press selects the matching
 * petal, gamepad A (or Enter / d-pad center) confirms it, B dismisses the home; touch taps a
 * petal directly.
 *
 * DELIBERATE M3 DEVIATION: spatial d-pad selection instead of linear focus traversal — the
 * whole point of the layout is direction-equals-destination.
 */
@Composable
fun HomeFlower(
    profileName: String?,
    onOpenProfile: () -> Unit,
    onEditOverlay: () -> Unit,
    onEditControls: () -> Unit,
    onDismiss: () -> Unit,
    settingsEntries: List<HomeMenuEntry>,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<FlowerDirection?>(null) }
    var settingsMenuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    fun select(dir: FlowerDirection) {
        if (selected != dir) {
            selected = dir
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun activate(dir: FlowerDirection) {
        selected = dir
        Log.d(TAG, "activate petal: $dir")
        when (dir) {
            FlowerDirection.Up -> onOpenProfile()
            FlowerDirection.Left -> onEditOverlay()
            FlowerDirection.Right -> onEditControls()
            FlowerDirection.Down -> settingsMenuOpen = true
        }
    }

    // An invisible anchor holds focus so physical d-pad / A / B key events reach the preview
    // handler on the root. Re-grab focus whenever the settings menu (a focusable popup that
    // steals window focus) closes. Retry a few frames — window focus can lag composition.
    val focusAnchor = remember { FocusRequester() }
    LaunchedEffect(settingsMenuOpen) {
        if (!settingsMenuOpen) {
            repeat(8) {
                delay(16)
                if (runCatching { focusAnchor.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> {
                    Log.d(TAG, "key: dpad up -> select Up"); select(FlowerDirection.Up); true
                }
                Key.DirectionLeft -> {
                    Log.d(TAG, "key: dpad left -> select Left"); select(FlowerDirection.Left); true
                }
                Key.DirectionRight -> {
                    Log.d(TAG, "key: dpad right -> select Right"); select(FlowerDirection.Right); true
                }
                Key.DirectionDown -> {
                    Log.d(TAG, "key: dpad down -> select Down"); select(FlowerDirection.Down); true
                }
                Key.ButtonA, Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                    Log.d(TAG, "key: confirm (${e.key}) with selected=$selected")
                    selected?.also { activate(it) } != null
                }
                Key.ButtonB -> {
                    Log.d(TAG, "key: B -> dismiss home"); onDismiss(); true
                }
                else -> false
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(1.dp).focusRequester(focusAnchor).focusable())

        val side = minOf(maxWidth, maxHeight)
        val gap = side * 0.035f
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            PetalCard(
                label = "Profile",
                supporting = profileName ?: "None",
                icon = Icons.Filled.Person,
                accent = ProfileAccent,
                selected = selected == FlowerDirection.Up,
                onTap = { activate(FlowerDirection.Up) },
                modifier = Modifier.size(width = side * 0.5f, height = side * 0.24f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                PetalCard(
                    label = "Overlay",
                    supporting = "On-screen buttons",
                    icon = Icons.Filled.Layers,
                    accent = OverlayAccent,
                    selected = selected == FlowerDirection.Left,
                    onTap = { activate(FlowerDirection.Left) },
                    modifier = Modifier.size(side * 0.3f),
                )
                DpadGlyph(selected = selected, size = side * 0.2f)
                PetalCard(
                    label = "Remapping",
                    supporting = "Physical controls",
                    icon = Icons.Filled.SportsEsports,
                    accent = RemapAccent,
                    selected = selected == FlowerDirection.Right,
                    onTap = { activate(FlowerDirection.Right) },
                    modifier = Modifier.size(side * 0.3f),
                )
            }
            Box {
                PetalCard(
                    label = "Settings",
                    supporting = "Everything else",
                    icon = Icons.Filled.Settings,
                    accent = SettingsAccent,
                    selected = selected == FlowerDirection.Down,
                    onTap = { activate(FlowerDirection.Down) },
                    modifier = Modifier.size(width = side * 0.5f, height = side * 0.24f),
                )
                FlowerSettingsMenu(
                    expanded = settingsMenuOpen,
                    entries = settingsEntries,
                    onDismissRequest = { settingsMenuOpen = false },
                )
            }
        }
    }
}

/** One option petal: accent-tinted rounded card with icon, label, one-line helper subtext. */
@Composable
private fun PetalCard(
    label: String,
    supporting: String?,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sel by animateFloatAsState(if (selected) 1f else 0f, tween(150), label = "petalSel")
    val shape = RoundedCornerShape(18.dp)
    // Accent tint composited over surfaceContainerLow — deliberate identity coloring, readable
    // in both themes because text/icons stay on theme roles + the raw accent.
    val container = accent.copy(alpha = 0.12f + 0.14f * sel)
        .compositeOver(MaterialTheme.colorScheme.surfaceContainerLow)
    Box(
        modifier
            .graphicsLayer {
                val scale = 1f + 0.04f * sel
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(container)
            .border(lerp(1.dp, 2.5.dp, sel), accent.copy(alpha = 0.35f + 0.65f * sel), shape)
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (supporting != null) {
                // Profile's subtext is a user-typed name: bounded card + single line + ellipsis.
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The center d-pad glyph; the selected direction's arm lights in that petal's accent. */
@Composable
private fun DpadGlyph(
    selected: FlowerDirection?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outlineVariant
    val upC by armColor(selected == FlowerDirection.Up, ProfileAccent, "dpadUp")
    val leftC by armColor(selected == FlowerDirection.Left, OverlayAccent, "dpadLeft")
    val rightC by armColor(selected == FlowerDirection.Right, RemapAccent, "dpadRight")
    val downC by armColor(selected == FlowerDirection.Down, SettingsAccent, "dpadDown")
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val arm = s * 0.32f
        val r = CornerRadius(arm * 0.35f, arm * 0.35f)
        val vTop = Offset((s - arm) / 2f, 0f)
        val hTop = Offset(0f, (s - arm) / 2f)
        // Base cross.
        drawRoundRect(color = base, topLeft = vTop, size = Size(arm, s), cornerRadius = r)
        drawRoundRect(color = base, topLeft = hTop, size = Size(s, arm), cornerRadius = r)
        // Selected-arm highlight: a rounded rect from that edge past center.
        val half = (s + arm) / 2f
        drawRoundRect(color = upC, topLeft = vTop, size = Size(arm, half), cornerRadius = r)
        drawRoundRect(color = downC, topLeft = Offset((s - arm) / 2f, s - half), size = Size(arm, half), cornerRadius = r)
        drawRoundRect(color = leftC, topLeft = hTop, size = Size(half, arm), cornerRadius = r)
        drawRoundRect(color = rightC, topLeft = Offset(s - half, (s - arm) / 2f), size = Size(half, arm), cornerRadius = r)
        // Outline + center dimple.
        drawRoundRect(color = outline, topLeft = vTop, size = Size(arm, s), cornerRadius = r, style = Stroke(1.dp.toPx()))
        drawRoundRect(color = outline, topLeft = hTop, size = Size(s, arm), cornerRadius = r, style = Stroke(1.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.12f), radius = arm * 0.28f, center = Offset(s / 2f, s / 2f))
    }
}

@Composable
private fun armColor(lit: Boolean, accent: Color, label: String) =
    animateColorAsState(
        targetValue = if (lit) accent.copy(alpha = 0.9f) else Color.Transparent,
        animationSpec = tween(150),
        label = label,
    )

/**
 * The misc-destinations fly-out, opening upward centered above the Settings petal. A custom
 * Popup (not DropdownMenu) because the activity's FLAG_LAYOUT_NO_LIMITS window confuses the
 * stock position provider — same reason as MainBottomToolbar.UpwardMenu.
 */
@Composable
private fun FlowerSettingsMenu(
    expanded: Boolean,
    entries: List<HomeMenuEntry>,
    onDismissRequest: () -> Unit,
) {
    if (!expanded) return
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val provider = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    // The platform moves focus between items on d-pad; A isn't a Compose confirm key, so track
    // the focused entry and fire it on ButtonA ourselves (Enter / d-pad center work natively).
    var focusedId by remember { mutableStateOf<String?>(null) }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        // focusable so the menu can be d-pad-navigated by physical controls.
        properties = PopupProperties(focusable = true),
    ) {
        // Shadow on a wrapper Box per the drop-shadow doctrine (not Surface elevation).
        Box(
            Modifier
                .softDropShadow(cornerRadius = 8.dp, offsetY = 0.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.ButtonA -> {
                            Log.d(TAG, "menu key: A with focused=$focusedId")
                            entries.firstOrNull { it.id == focusedId }
                                ?.also { onDismissRequest(); it.onClick() } != null
                        }
                        Key.ButtonB -> {
                            Log.d(TAG, "menu key: B -> close menu"); onDismissRequest(); true
                        }
                        else -> false
                    }
                },
        ) {
            // surfaceContainerHighest — menu container (canonical M3 menu surface).
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 3.dp,
            ) {
                Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp)) {
                    entries.forEach { entry ->
                        var focused by remember { mutableStateOf(false) }
                        val bgAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "homeMenuFocus")
                        DropdownMenuItem(
                            modifier = Modifier
                                .onFocusChanged {
                                    focused = it.hasFocus
                                    if (it.hasFocus) focusedId = entry.id
                                }
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = bgAlpha)),
                            text = { Text(entry.label) },
                            leadingIcon = { Icon(entry.icon, contentDescription = null) },
                            onClick = { onDismissRequest(); entry.onClick() },
                        )
                    }
                }
            }
        }
    }
}
