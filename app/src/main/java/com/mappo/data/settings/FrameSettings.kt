package com.mappo.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-tunable styling for the handheld frame chrome (`HandheldFrame`) — the faux-hardware
 * detail pass (2026-07-14) simulating a physical device: plastic grain, edge lighting, the
 * shell↔glass gap, and the screen bezel.
 *
 * Color hierarchy: [shellColor] and [glassColor] are CORE colors from which their layer's
 * highlights/shadows derive (the remap-pill bevel principle — lerp toward white/black by the
 * intensity); [bezelColor] is its own standalone core (the screen's flat dark border derives
 * from neither shell nor glass). Intensities are 0..1 sliders.
 */
data class FrameStyle(
    /** Core plastic color of the outer shell (annotation 1 of the reference photo). */
    val shellColor: Color = Color(0xFFEFECE6),
    /** Core color of the inner glass frame (annotation 5). */
    val glassColor: Color = Color(0xFF5F656C),
    /** Flat dark border around the screen itself (annotation 7) — standalone core color. */
    val bezelColor: Color = Color(0xFF14161B),
    /** Plastic grain visibility on the shell (annotation 1's texture). */
    val shellTexture: Float = 0.25f,
    /** Light catching the shell's rounded edges (annotation 2). */
    val shellHighlight: Float = 0.45f,
    /** Darkening where the shell's edges finish rounding away (annotation 3). */
    val shellShadow: Float = 0.35f,
    /** The dark gap between shell and glass — two separate physical parts (annotation 4). */
    val well: Float = 0.75f,
    /** Light on the glass frame's slightly rounded edges (annotation 6). */
    val glassHighlight: Float = 0.35f,
    /** Corner shading on the bezel's inner edge — passive-LCD square vignette (annotation 8). */
    val vignette: Float = 0.30f,
)

/**
 * Persistence + live-preview channel for [FrameStyle]. Same pattern as [AutoSwitchSettings]
 * (SharedPreferences singleton exposing a StateFlow), with one extra affordance: [preview]
 * updates the flow WITHOUT touching prefs so slider drags restyle the frame live at zero
 * write cost; the screen commits via [set] on drag end.
 */
@Singleton
class FrameSettings @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _style = MutableStateFlow(readStyle())
    val style: StateFlow<FrameStyle> = _style.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith(KEY_PREFIX)) _style.value = readStyle()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /** Restyle the frame live without persisting (slider drag); commit with [set]. */
    fun preview(style: FrameStyle) {
        _style.value = style
    }

    fun set(style: FrameStyle) {
        prefs.edit()
            .putInt(KEY_SHELL_COLOR, style.shellColor.toArgb())
            .putInt(KEY_GLASS_COLOR, style.glassColor.toArgb())
            .putInt(KEY_BEZEL_COLOR, style.bezelColor.toArgb())
            .putFloat(KEY_SHELL_TEXTURE, style.shellTexture)
            .putFloat(KEY_SHELL_HIGHLIGHT, style.shellHighlight)
            .putFloat(KEY_SHELL_SHADOW, style.shellShadow)
            .putFloat(KEY_WELL, style.well)
            .putFloat(KEY_GLASS_HIGHLIGHT, style.glassHighlight)
            .putFloat(KEY_VIGNETTE, style.vignette)
            .apply()
        // Immediate flow update; the pref listener re-reads to the identical value.
        _style.value = style
    }

    fun reset() {
        prefs.edit().apply { ALL_KEYS.forEach(::remove) }.apply()
        _style.value = FrameStyle()
    }

    private fun readStyle(): FrameStyle {
        val d = FrameStyle()
        return FrameStyle(
            shellColor = Color(prefs.getInt(KEY_SHELL_COLOR, d.shellColor.toArgb())),
            glassColor = Color(prefs.getInt(KEY_GLASS_COLOR, d.glassColor.toArgb())),
            bezelColor = Color(prefs.getInt(KEY_BEZEL_COLOR, d.bezelColor.toArgb())),
            shellTexture = prefs.getFloat(KEY_SHELL_TEXTURE, d.shellTexture),
            shellHighlight = prefs.getFloat(KEY_SHELL_HIGHLIGHT, d.shellHighlight),
            shellShadow = prefs.getFloat(KEY_SHELL_SHADOW, d.shellShadow),
            well = prefs.getFloat(KEY_WELL, d.well),
            glassHighlight = prefs.getFloat(KEY_GLASS_HIGHLIGHT, d.glassHighlight),
            vignette = prefs.getFloat(KEY_VIGNETTE, d.vignette),
        )
    }

    companion object {
        private const val PREFS_NAME = "mappo_settings"
        private const val KEY_PREFIX = "frame_"
        private const val KEY_SHELL_COLOR = "frame_shell_color"
        private const val KEY_GLASS_COLOR = "frame_glass_color"
        private const val KEY_BEZEL_COLOR = "frame_bezel_color"
        private const val KEY_SHELL_TEXTURE = "frame_shell_texture"
        private const val KEY_SHELL_HIGHLIGHT = "frame_shell_highlight"
        private const val KEY_SHELL_SHADOW = "frame_shell_shadow"
        private const val KEY_WELL = "frame_well"
        private const val KEY_GLASS_HIGHLIGHT = "frame_glass_highlight"
        private const val KEY_VIGNETTE = "frame_vignette"

        private val ALL_KEYS = listOf(
            KEY_SHELL_COLOR, KEY_GLASS_COLOR, KEY_BEZEL_COLOR,
            KEY_SHELL_TEXTURE, KEY_SHELL_HIGHLIGHT, KEY_SHELL_SHADOW,
            KEY_WELL, KEY_GLASS_HIGHLIGHT, KEY_VIGNETTE,
        )
    }
}
