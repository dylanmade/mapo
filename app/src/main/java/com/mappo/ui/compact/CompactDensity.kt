package com.mappo.ui.compact

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The density scale for Mappo's compact M3 component set.
 *
 * Material 3 Compose never ported the web/Angular "density scale" (default / comfortable
 * / compact) into a first-class API, and most M3 components either don't expose their size
 * tokens at all or only expose `contentPadding`. Rather than fork components or hand-roll a
 * one-off slimmer row each time a screen feels too chunky (the drift we'd started to
 * accumulate across [com.mappo.ui.components] and [com.mappo.ui.component]), every compact
 * variant in this package reads its dimensions from a single [CompactDensity] token bag
 * provided through [LocalCompactDensity].
 *
 * Three presets ship: [Comfortable] (~stock M3, the reference baseline), [Compact] (M3-dense),
 * and [Dense] (desktop/IDE-dense). Wrap any subtree in [ProvideCompactDensity] to switch the
 * whole subtree at once — that's the knob the gallery screen flips at runtime so we can A/B
 * the levels on-device before committing to one as the app default.
 *
 * Every field here is a *visual* token. The accessibility hit-target floor is governed
 * separately by [enforceMinTouchTarget], which feeds M3's [LocalMinimumInteractiveComponentSize]
 * so the choice applies to stock M3 widgets nested inside a provided subtree too.
 */
@Immutable
data class CompactDensity(
    /** Human-readable name, surfaced in the gallery selector. */
    val label: String,

    // ── Text fields ───────────────────────────────────────────────────────────
    /** Minimum height of a single-line [CompactTextField] box (default M3 ≈ 56dp). */
    val fieldMinHeight: Dp,
    /** Inner padding between the field border and its text / icons. */
    val fieldContentPadding: PaddingValues,

    // ── Buttons ───────────────────────────────────────────────────────────────
    /** Minimum height for the compact button family (default M3 = 40dp). */
    val buttonMinHeight: Dp,
    /** Content padding inside compact buttons (default M3 ≈ 24dp horizontal). */
    val buttonContentPadding: PaddingValues,
    /** Edge length of a [CompactIconButton]'s visual + clickable box. */
    val iconButtonSize: Dp,

    // ── List / settings rows ────────────────────────────────────────────────────
    /**
     * Minimum **total** height of a [CompactListItem] — a tappability floor for the whole row,
     * applied outside the padding. Above this floor the row grows naturally with its content
     * while [listItemPaddingVertical] stays constant (so every row gets identical top/bottom
     * whitespace and multi-line rows aren't crammed). The floor only adds slack for rows shorter
     * than it; a one-line text row at [listItemPaddingVertical] typically already clears it.
     */
    val listItemMinHeight: Dp,
    /** Horizontal inset on a [CompactListItem]. */
    val listItemPaddingHorizontal: Dp,
    /** Vertical inset on a [CompactListItem] (added above + below the content). */
    val listItemPaddingVertical: Dp,
    /**
     * Vertical gap between a [CompactListItem]'s headline and its supporting line. M3 stacks
     * them with no explicit gap (line-height does the work), which reads a touch tight; a small
     * value here gives the supporting text a little breathing room.
     */
    val listItemTextSpacing: Dp,

    // ── Controls ────────────────────────────────────────────────────────────────
    /**
     * Uniform scale applied to [CompactSwitch] — the M3 Switch exposes no size token. Unlike a
     * plain `Modifier.scale`, [CompactSwitch] scales the switch's *measured* bounds too, so a
     * smaller switch genuinely reclaims row space instead of leaving a phantom full-size box.
     */
    val switchScale: Float,
    /**
     * When false, [CompactSwitch] drops the 48dp minimum-interactive reservation so a trailing
     * switch no longer inflates a single-line row past its text height. This is the lever that
     * lets a single-line row with a switch sit at the same height as a two-line text row. When
     * true, the switch keeps the 48dp reservation (stock behavior).
     */
    val switchReserveMinTouch: Boolean,
    /**
     * When true, [CompactSlider] uses the stock M3 line-handle thumb + default track (the
     * "ordinary" Material slider), with the handle height overridden to [sliderThumbHeight].
     * When false, it uses a compact circular thumb + a flat thin track ([sliderThumbSize] /
     * [sliderTrackHeight]).
     */
    val sliderUseDefaultThumb: Boolean,
    /** Height of the stock line handle when [sliderUseDefaultThumb] is true (M3 default = 44dp). */
    val sliderThumbHeight: Dp,
    /** Diameter of the compact circular [CompactSlider] thumb (custom path only). */
    val sliderThumbSize: Dp,
    /** Track thickness of the compact flat [CompactSlider] track (custom path only). */
    val sliderTrackHeight: Dp,

    // ── Menus ───────────────────────────────────────────────────────────────────
    /** Minimum height of a [CompactDropdownMenuItem] (M3 menu item floor = 48dp). */
    val menuItemMinHeight: Dp,
    /** Content padding inside a [CompactDropdownMenuItem]. */
    val menuItemContentPadding: PaddingValues,

    // ── Cross-cutting ───────────────────────────────────────────────────────────
    /**
     * When true, interactive widgets keep M3's 48dp minimum touch target even when their
     * visual box is smaller (the box shrinks, the hit area doesn't). When false, hit areas
     * collapse to the visual size so rows/controls can pack edge-to-edge — denser, but
     * less forgiving for thumbs. Drives [LocalMinimumInteractiveComponentSize].
     */
    val enforceMinTouchTarget: Boolean,
    /**
     * When true, compact components step primary text down one M3 role (bodyLarge → bodyMedium,
     * labelLarge → labelMedium) so the type matches the tighter metrics. Off for the looser
     * presets where the default roles still breathe.
     */
    val useSmallerText: Boolean,
) {
    companion object {
        /**
         * Essentially stock M3 — the reference point. Use it to confirm a compact layout
         * still reads correctly at default density, and as the "off" state when A/B testing.
         */
        val Comfortable = CompactDensity(
            label = "Comfortable",
            fieldMinHeight = 56.dp,
            fieldContentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            buttonMinHeight = 40.dp,
            buttonContentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            iconButtonSize = 48.dp,
            listItemMinHeight = 56.dp, // one-line M3 ListItem height; floor for the whole row
            listItemPaddingHorizontal = 16.dp,
            listItemPaddingVertical = 8.dp,
            listItemTextSpacing = 0.dp,
            switchScale = 1f,
            switchReserveMinTouch = true,
            sliderUseDefaultThumb = true,
            sliderThumbHeight = 44.dp,
            sliderThumbSize = 20.dp,
            sliderTrackHeight = 16.dp,
            menuItemMinHeight = 48.dp,
            menuItemContentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            enforceMinTouchTarget = true,
            useSmallerText = false,
        )

        /**
         * M3-dense. Noticeably tighter than stock but still unmistakably Material; keeps the
         * 48dp touch-target floor so it stays thumb-friendly. The expected app-wide default.
         */
        val Compact = CompactDensity(
            label = "Compact",
            fieldMinHeight = 40.dp,
            fieldContentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            buttonMinHeight = 36.dp,
            buttonContentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            iconButtonSize = 40.dp,
            listItemMinHeight = 44.dp, // floor for the whole row
            listItemPaddingHorizontal = 16.dp,
            listItemPaddingVertical = 6.dp,
            listItemTextSpacing = 0.dp,
            switchScale = 0.85f,
            switchReserveMinTouch = true,
            sliderUseDefaultThumb = false,
            sliderThumbHeight = 44.dp,
            sliderThumbSize = 16.dp,
            sliderTrackHeight = 8.dp,
            menuItemMinHeight = 40.dp,
            menuItemContentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            enforceMinTouchTarget = true,
            useSmallerText = false,
        )

        /**
         * Dylan's Cut — the intended app default. Stock-M3 [Comfortable] proportions, with a few
         * deliberate tweaks settled on iteratively:
         *  - list rows use **constant vertical padding** (12dp) and grow with their content, so
         *    every row has identical top/bottom whitespace and multi-line rows never look
         *    crammed. The 48dp [listItemMinHeight] is just a tappability floor;
         *  - the switch is scaled to 0.85× ([switchScale]) — a touch smaller — and drops its
         *    48dp min-interactive reservation ([switchReserveMinTouch] = false). With both, it
         *    contributes only ~27dp to a row instead of a 48dp touch-target halo, so a switch row
         *    is ~51dp — right next to the ~48dp text rows — rather than ballooning to ~72dp.
         *    (Cost: the switch's own tap target is ~27dp tall; whole-row clicks are unaffected.);
         *  - a 2dp gap between a row's headline and its supporting line ([listItemTextSpacing]);
         *  - the stock M3 line-handle slider, but with the handle shortened from 44dp to 40dp.
         *
         * Text fields stay at the standard ~56dp here; the slim field is opt-in per call via
         * [CompactTextField]'s `size = CompactFieldSize.Slim`, not the density default.
         */
        val DylansCut = Comfortable.copy(
            label = "Dylan's Cut",
            listItemMinHeight = 48.dp,
            listItemPaddingVertical = 12.dp,
            listItemTextSpacing = 2.dp,
            switchScale = 0.85f,
            switchReserveMinTouch = false,
            sliderThumbHeight = 40.dp,
        )

        /** All presets in scale order — drives the gallery's segmented selector. */
        val all = listOf(Comfortable, Compact, DylansCut)
    }
}

/**
 * The active compact density for a subtree. Defaults to [CompactDensity.Compact] so a compact
 * widget dropped anywhere — even outside an explicit [ProvideCompactDensity] — renders dense
 * rather than silently falling back to stock proportions. `static` because density flips are
 * deliberate, subtree-wide events, not per-recomposition reads.
 */
val LocalCompactDensity = staticCompositionLocalOf { CompactDensity.Compact }

/**
 * Provide [density] (and the matching touch-target floor) to [content]. This is the one knob
 * to flip a whole screen — or the entire app, if wrapped high enough — between density levels.
 *
 * Providing [LocalMinimumInteractiveComponentSize] here means the touch-target decision also
 * reaches stock M3 widgets nested in the subtree (e.g. a plain [androidx.compose.material3.Switch]),
 * not just this package's compact variants.
 */
@Composable
fun ProvideCompactDensity(
    density: CompactDensity,
    content: @Composable () -> Unit,
) {
    val minTouch: Dp = if (density.enforceMinTouchTarget) 48.dp else Dp.Unspecified
    CompositionLocalProvider(
        LocalCompactDensity provides density,
        LocalMinimumInteractiveComponentSize provides minTouch,
        content = content,
    )
}

/**
 * The primary text style for a compact component at the current density: [Typography.bodyLarge]
 * normally, stepped down to [Typography.bodyMedium] when [CompactDensity.useSmallerText] is set.
 */
@Composable
@ReadOnlyComposable
internal fun compactBodyStyle(): TextStyle =
    if (LocalCompactDensity.current.useSmallerText) MaterialTheme.typography.bodyMedium
    else MaterialTheme.typography.bodyLarge

/**
 * The label style for compact buttons / controls: [Typography.labelLarge], stepped down to
 * [Typography.labelMedium] when [CompactDensity.useSmallerText] is set.
 */
@Composable
@ReadOnlyComposable
internal fun compactLabelStyle(): TextStyle =
    if (LocalCompactDensity.current.useSmallerText) MaterialTheme.typography.labelMedium
    else MaterialTheme.typography.labelLarge
