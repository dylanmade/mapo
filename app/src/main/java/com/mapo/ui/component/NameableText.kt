package com.mapo.ui.component

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The standard max width for a **player-nameable** string (profile / action set / layer / keyboard
 * names — anything the user can type). Names are unbounded, so every place one is interpolated must
 * cap its width and ellipsize rather than wrap or push surrounding chrome around. Tight containers
 * (e.g. the collapsed navigation rail, ~96dp wide) pass a smaller [NameableText.maxWidth].
 */
val NameableLabelMaxWidth: Dp = 160.dp

/**
 * Renders a user-named string with the project-standard overflow treatment: a single line, no soft
 * wrap, ellipsis at the end, and a hard width cap ([maxWidth], default [NameableLabelMaxWidth]).
 * Use this — never a bare [Text] — for any value the user can name. [style] defaults to the ambient
 * [LocalTextStyle] so it inherits the slot it's dropped into (app-bar title, button, rail label…).
 */
@Composable
fun NameableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    maxWidth: Dp = NameableLabelMaxWidth,
) {
    Text(
        text = text,
        modifier = modifier.widthIn(max = maxWidth),
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
