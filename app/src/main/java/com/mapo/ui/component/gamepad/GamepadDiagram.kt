package com.mapo.ui.component.gamepad

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapo.data.model.RemapTarget
import com.mapo.data.model.steam.BindingOutput

/**
 * A tappable vector abstraction of an Xbox-layout gamepad. Each control is a hit target that
 * emits the corresponding [RemapTarget.Gamepad] token on tap; the control currently matching
 * [current] is highlighted with the primary container color.
 *
 * Covers the 16 physical [com.mapo.data.model.DeviceButton]s (face buttons, bumpers, triggers,
 * stick clicks, d-pad, Start/Select). The 8 analog **stick-direction** tokens
 * (`LSTICK_UP`/… , `RSTICK_…`) are deliberately not drawn here — they stay reachable through the
 * picker's text filter (type "stick"); a directional-hotspot pass can add them later.
 *
 * Layout is normalized (0..1) and fitted to a ~1.45:1 box, so it scales across screen sizes;
 * tap targets have a dp floor so they stay usable on small displays. Colors are pure M3 roles —
 * face buttons are intentionally NOT Xbox-branded (green/red/blue/yellow) to honor the
 * theme-roles-only rule.
 */
@Composable
fun GamepadDiagram(
    current: BindingOutput,
    onSelect: (RemapTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    // surfaceContainerHigh — controller "shell" silhouette drawn behind the controls
    val shell = MaterialTheme.colorScheme.surfaceContainerHigh

    BoxWithConstraints(
        modifier = modifier.padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val aspect = 1.45f
        // Fit a constant-aspect content box inside the available space.
        val cw: Dp = if (maxWidth <= maxHeight * aspect) maxWidth else maxHeight * aspect
        val ch: Dp = cw / aspect

        // Tap-target sizes scale with the box but never drop below a usable floor.
        val faceDia = (cw * 0.115f).coerceAtLeast(44.dp)
        val stickDia = (cw * 0.17f).coerceAtLeast(54.dp)
        val pillW = (cw * 0.17f).coerceAtLeast(54.dp)
        val pillH = (cw * 0.08f).coerceAtLeast(30.dp)
        val centerW = (cw * 0.13f).coerceAtLeast(46.dp)
        val centerH = (cw * 0.06f).coerceAtLeast(26.dp)
        val crossSize = (cw * 0.22f).coerceAtLeast(100.dp)

        // Centers a w×h control on the normalized point (nx, ny) within the content box.
        fun place(nx: Float, ny: Float, w: Dp, h: Dp): Modifier =
            Modifier.offset(x = cw * nx - w / 2, y = ch * ny - h / 2).size(w, h)

        fun selected(token: String): Boolean =
            BindingOutput.fromRemapTarget(RemapTarget.Gamepad(token)) == current

        Box(
            modifier = Modifier
                .size(cw, ch)
                .drawBehind {
                    val r = CornerRadius(28.dp.toPx())
                    // Main body
                    drawRoundRect(
                        color = shell,
                        topLeft = Offset(size.width * 0.06f, size.height * 0.04f),
                        size = Size(size.width * 0.88f, size.height * 0.56f),
                        cornerRadius = r,
                    )
                    // Left + right grips
                    drawRoundRect(
                        color = shell,
                        topLeft = Offset(size.width * 0.05f, size.height * 0.34f),
                        size = Size(size.width * 0.34f, size.height * 0.62f),
                        cornerRadius = r,
                    )
                    drawRoundRect(
                        color = shell,
                        topLeft = Offset(size.width * 0.61f, size.height * 0.34f),
                        size = Size(size.width * 0.34f, size.height * 0.62f),
                        cornerRadius = r,
                    )
                },
        ) {
            // Triggers (top row), then bumpers below them.
            LabelButton("AXIS_L2", "LT", selected("AXIS_L2"), CapsuleShape, place(0.20f, 0.05f, pillW, pillH)) { onSelect(RemapTarget.Gamepad("AXIS_L2")) }
            LabelButton("AXIS_R2", "RT", selected("AXIS_R2"), CapsuleShape, place(0.80f, 0.05f, pillW, pillH)) { onSelect(RemapTarget.Gamepad("AXIS_R2")) }
            LabelButton("BUTTON_L1", "LB", selected("BUTTON_L1"), CapsuleShape, place(0.20f, 0.14f, pillW, pillH)) { onSelect(RemapTarget.Gamepad("BUTTON_L1")) }
            LabelButton("BUTTON_R1", "RB", selected("BUTTON_R1"), CapsuleShape, place(0.80f, 0.14f, pillW, pillH)) { onSelect(RemapTarget.Gamepad("BUTTON_R1")) }

            // Left stick (upper-left), d-pad (lower-left).
            StickButton("BUTTON_THUMBL", "L3", selected("BUTTON_THUMBL"), place(0.235f, 0.40f, stickDia, stickDia)) { onSelect(RemapTarget.Gamepad("BUTTON_THUMBL")) }
            DPadCross(crossSize, place(0.235f, 0.70f, crossSize, crossSize), ::selected, onSelect)

            // Face diamond (upper-right), right stick (lower-right).
            LabelButton("BUTTON_Y", "Y", selected("BUTTON_Y"), CircleShape, place(0.765f, 0.305f, faceDia, faceDia)) { onSelect(RemapTarget.Gamepad("BUTTON_Y")) }
            LabelButton("BUTTON_X", "X", selected("BUTTON_X"), CircleShape, place(0.685f, 0.40f, faceDia, faceDia)) { onSelect(RemapTarget.Gamepad("BUTTON_X")) }
            LabelButton("BUTTON_B", "B", selected("BUTTON_B"), CircleShape, place(0.845f, 0.40f, faceDia, faceDia)) { onSelect(RemapTarget.Gamepad("BUTTON_B")) }
            LabelButton("BUTTON_A", "A", selected("BUTTON_A"), CircleShape, place(0.765f, 0.495f, faceDia, faceDia)) { onSelect(RemapTarget.Gamepad("BUTTON_A")) }
            StickButton("BUTTON_THUMBR", "R3", selected("BUTTON_THUMBR"), place(0.765f, 0.70f, stickDia, stickDia)) { onSelect(RemapTarget.Gamepad("BUTTON_THUMBR")) }

            // Center cluster: Select (View) / Start (Menu).
            LabelButton("BUTTON_SELECT", "Select", selected("BUTTON_SELECT"), CapsuleShape, place(0.43f, 0.27f, centerW, centerH), labelStyle = LabelSize.Small) { onSelect(RemapTarget.Gamepad("BUTTON_SELECT")) }
            LabelButton("BUTTON_START", "Start", selected("BUTTON_START"), CapsuleShape, place(0.57f, 0.27f, centerW, centerH), labelStyle = LabelSize.Small) { onSelect(RemapTarget.Gamepad("BUTTON_START")) }
        }
    }
}

private val CapsuleShape: Shape = RoundedCornerShape(percent = 50)

private enum class LabelSize { Normal, Small }

/** A tappable themed control showing a short text label (face buttons, bumpers, triggers, menu keys). */
@Composable
private fun LabelButton(
    token: String,
    label: String,
    selected: Boolean,
    shape: Shape,
    modifier: Modifier,
    labelStyle: LabelSize = LabelSize.Normal,
    onClick: () -> Unit,
) {
    GamepadControl(token, selected, shape, modifier, onClick) { contentColor ->
        Text(
            text = label,
            style = if (labelStyle == LabelSize.Small) MaterialTheme.typography.labelSmall
                    else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

/** A circular stick control with a concentric inner disc to read as a thumbstick, plus its label. */
@Composable
private fun StickButton(
    token: String,
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    GamepadControl(token, selected, CircleShape, modifier, onClick) { contentColor ->
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            // Inner disc — a cheap thumbstick read; tint follows the content color at low alpha.
            Surface(
                shape = CircleShape,
                color = contentColor.copy(alpha = 0.18f),
                modifier = Modifier.fillMaxSize().padding(10.dp),
            ) {}
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
        }
    }
}

/** The d-pad cross: four tappable arms around an inert center hub (corners empty). */
@Composable
private fun DPadCross(
    crossSize: Dp,
    modifier: Modifier,
    selected: (String) -> Boolean,
    onSelect: (RemapTarget) -> Unit,
) {
    Box(modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                Spacer(Modifier.weight(1f))
                DPadArm("DPAD_UP", "D-pad up", Icons.Filled.KeyboardArrowUp, selected("DPAD_UP"), Modifier.weight(1f).fillMaxSize()) { onSelect(RemapTarget.Gamepad("DPAD_UP")) }
                Spacer(Modifier.weight(1f))
            }
            Row(Modifier.weight(1f).fillMaxSize()) {
                DPadArm("DPAD_LEFT", "D-pad left", Icons.Filled.KeyboardArrowLeft, selected("DPAD_LEFT"), Modifier.weight(1f).fillMaxSize()) { onSelect(RemapTarget.Gamepad("DPAD_LEFT")) }
                // surfaceContainerHighest — inert center hub
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.weight(1f).fillMaxSize()) {}
                DPadArm("DPAD_RIGHT", "D-pad right", Icons.Filled.KeyboardArrowRight, selected("DPAD_RIGHT"), Modifier.weight(1f).fillMaxSize()) { onSelect(RemapTarget.Gamepad("DPAD_RIGHT")) }
            }
            Row(Modifier.weight(1f).fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                Spacer(Modifier.weight(1f))
                DPadArm("DPAD_DOWN", "D-pad down", Icons.Filled.KeyboardArrowDown, selected("DPAD_DOWN"), Modifier.weight(1f).fillMaxSize()) { onSelect(RemapTarget.Gamepad("DPAD_DOWN")) }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DPadArm(
    token: String,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    GamepadControl(token, selected, RoundedCornerShape(6.dp), modifier, onClick) { contentColor ->
        Icon(icon, contentDescription = contentDescription, tint = contentColor)
    }
}

/**
 * Shared chrome for every gamepad control: a tappable [Surface] whose container animates to the
 * primary role when [selected]. The [content] receives the resolved content color so labels and
 * icons stay legible in both states.
 */
@Composable
private fun GamepadControl(
    token: String,
    selected: Boolean,
    shape: Shape,
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable (contentColor: androidx.compose.ui.graphics.Color) -> Unit,
) {
    // primary when selected, else surfaceContainerHighest (a "raised key" over the shell)
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(durationMillis = 150),
        label = "gamepadControlContainer",
    )
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = shape,
        color = container,
        contentColor = contentColor,
        modifier = modifier.testTag("gamepad_btn_$token"),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            content(contentColor)
        }
    }
}
