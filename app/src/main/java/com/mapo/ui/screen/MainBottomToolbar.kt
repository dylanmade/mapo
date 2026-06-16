package com.mapo.ui.screen

import androidx.compose.foundation.gestures.detectTapGestures
import com.mapo.ui.component.NameableText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.mapo.ui.compact.scaledLayout

/**
 * The Mapo home: a compact, rounded floating toolbar near the bottom of the screen, with a small
 * non-interactive "tab" hanging off its bottom edge carrying the wordmark. The toolbar, left to right:
 *  - **Master switch** — turns Mapo's features (remap + overlay) on/off in lockstep (icons in the thumb).
 *  - **Split button** — the active profile name (leading; opens profile options) + an edit-pencil
 *    menu trigger (trailing) that opens the profile/navigation menu.
 *  - **Options button** — opens the (temporary) "more" menu of secondary destinations.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainBottomToolbar(
    profileName: String,
    steamAccountName: String?,
    mapoEnabled: Boolean,
    onSetMapoEnabled: (Boolean) -> Unit,
    onEditOverlay: () -> Unit,
    onEditControls: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAutoSwitch: () -> Unit,
    onOpenBlocklist: () -> Unit,
    onOpenThemeStudio: () -> Unit,
    onOpenShizukuSetup: () -> Unit,
    onOpenSteamSetup: () -> Unit,
    onOpenCompactGallery: () -> Unit,
    onOpenColorPickerDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    // The "Medium" split button actually renders at 40dp — its content padding has no vertical
    // component, so it never reaches the nominal 56dp and sits at the 40dp min-height floor. Match
    // the whole toolbar to that 40dp: the stock FilledTonalIconButton is already a 40dp button, and
    // the 32dp switch track is scaled up 1.25× (measure-scaling) to reach 40.
    val mediumStyle = SplitButtonDefaults.MediumContainerHeight
    val switchScale = 40f / 32f

    Column(
        // Consume taps on the home chrome (pill gaps, the tab) so they don't fall through to the
        // dismiss catcher behind and background Mapo. Child controls are hit-tested first.
        modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Toolbar pill ──
        // The shadow lives on a wrapper Box, NOT the Surface — the Surface's shape clip would crop
        // a shadow drawn on its own modifier (this was the "gap"). Same pattern as the overlay
        // editor's menu. offsetY = 0 → a symmetric shadow that hugs the capsule evenly.
        Box(modifier = Modifier.softDropShadow(cornerRadius = 100.dp, offsetY = 0.dp)) {
            // surfaceContainer — floating toolbar (matches M3 FloatingToolbar's standard container role).
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ── Master enable switch ──
                    // Strip the 48dp min-interactive box (so the footprint is the 32dp track, not
                    // 48) then scale 1.25× to reach the 40dp toolbar height. Mirrors CompactSwitch.
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                        Switch(
                            checked = mapoEnabled,
                            onCheckedChange = onSetMapoEnabled,
                            modifier = Modifier.scaledLayout(switchScale),
                            thumbContent = {
                                Icon(
                                    imageVector = if (mapoEnabled) Icons.Filled.Check else Icons.Filled.Close,
                                    contentDescription = if (mapoEnabled) "Mapo features on" else "Mapo features off",
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    }

                    // ── Split button: profile name + menu (Small — defaults) ──
                    Box {
                        SplitButtonLayout(
                            leadingButton = {
                                SplitButtonDefaults.LeadingButton(
                                    onClick = onOpenProfile,
                                    shapes = SplitButtonDefaults.leadingButtonShapesFor(mediumStyle),
                                    contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(mediumStyle),
                                ) {
                                    // Profile name is user-typed: cap width + ellipsize (see NameableText).
                                    NameableText(profileName)
                                }
                            },
                            trailingButton = {
                                SplitButtonDefaults.TrailingButton(
                                    checked = menuExpanded,
                                    onCheckedChange = { menuExpanded = it },
                                    shapes = SplitButtonDefaults.trailingButtonShapesFor(mediumStyle),
                                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(mediumStyle),
                                ) {
                                    // Edit pencil at rest; open-chevron while the menu is up.
                                    Icon(
                                        imageVector = if (menuExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.Edit,
                                        contentDescription = if (menuExpanded) "Close menu" else "Profile menu",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                        UpwardMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            // Bottom-to-top (opens upward): Edit overlay nearest the thumb.
                            MenuItem("Profile options", Icons.Filled.Person) { menuExpanded = false; onOpenProfile() }
                            MenuItem("Edit controls", Icons.Filled.SportsEsports) { menuExpanded = false; onEditControls() }
                            MenuItem("Edit overlay", Icons.Filled.Edit) { menuExpanded = false; onEditOverlay() }
                        }
                    }

                    // ── Options button → temporary "more" menu ──
                    Box {
                        // Stock 40dp FilledTonalIconButton — already matches the toolbar height.
                        FilledTonalIconButton(onClick = { moreExpanded = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "More options")
                        }
                        UpwardMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            MenuItem("Auto switch", Icons.Filled.SwapHoriz) { moreExpanded = false; onOpenAutoSwitch() }
                            MenuItem("Blocklist", Icons.Filled.Block) { moreExpanded = false; onOpenBlocklist() }
                            MenuItem("Theme studio", Icons.Filled.Palette) { moreExpanded = false; onOpenThemeStudio() }
                            MenuItem("Shizuku setup", Icons.Filled.SecurityUpdateGood) { moreExpanded = false; onOpenShizukuSetup() }
                            MenuItem(if (steamAccountName != null) "Steam account" else "Connect to Steam", Icons.Filled.Person) { moreExpanded = false; onOpenSteamSetup() }
                            MenuItem("Compact component gallery", Icons.Filled.Dashboard) { moreExpanded = false; onOpenCompactGallery() }
                            MenuItem("Color picker (preview)", Icons.Filled.Colorize) { moreExpanded = false; onOpenColorPickerDemo() }
                        }
                    }
                }
            }
        }

        // ── Tab hanging off the toolbar's bottom edge: the placeholder wordmark. Same color + tonal
        // elevation as the pill so it reads as one connected shape; square top meets the capsule's
        // flat bottom, rounded bottom. No gap above it. ──
        Surface(
            shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
        ) {
            Text(
                text = "Mapo",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 5.dp),
            )
        }
    }
}

/**
 * A menu that opens UPWARD, right-aligned above its anchor. The standard DropdownMenu mispositions
 * here because the activity's `FLAG_LAYOUT_NO_LIMITS` window makes its position provider think
 * there's room below the bottom-anchored toolbar (off-screen), so it opens downward into the void.
 */
@Composable
private fun UpwardMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
                val x = (anchorBounds.right - popupContentSize.width)
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismissRequest,
        // focusable so the menu can be dpad-navigated by physical controls.
        properties = PopupProperties(focusable = true),
    ) {
        // Shadow on a wrapper Box per the drop-shadow doctrine (not Surface elevation).
        Box(Modifier.softDropShadow(cornerRadius = 8.dp, offsetY = 0.dp)) {
            // surfaceContainerHighest — menu container (canonical M3 menu surface).
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier.width(IntrinsicSize.Max).padding(vertical = 8.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
