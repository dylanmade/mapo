package com.mappo.ui.control

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow

/**
 * Mappo's hand-rolled miniature pill button, in the shared box treatment. [filled] (a
 * primary/commit action) keeps its emphasis through the stronger text color only. [elevated]
 * uses the topmost button plane for buttons sitting on a box/card background. Disabled =
 * dimmed + inert.
 */
@Composable
fun MappoPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    elevated: Boolean = false,
) {
    val content = if (filled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant
    val container = if (elevated) MappoElevatedContainer else mappoBoxContainer()
    val interaction = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        border = mappoBevelBorder(container, MappoPillHeight / 2),
        modifier = modifier
            .mappoInteractiveMotion(interaction)
            .height(MappoPillHeight)
            .then(
                if (enabled) {
                    Modifier.clip(RoundedCornerShape(50)).clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else Modifier.alpha(0.55f),
            ),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = MappoPillContentPadding)) {
            Text(
                text = text,
                style = mappoMiniTextStyle(),
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Mappo's hand-rolled miniature icon button (cogs etc.) — ripple-clipped circle, no 48dp halo. */
@Composable
fun MappoIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .mappoInteractiveMotion(interaction)
            .size(MappoIconButtonSize)
            .clip(CircleShape)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else Modifier.alpha(0.45f),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(MappoIconButtonIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
