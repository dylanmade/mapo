package com.themestudio.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.TypographyRole
import com.themestudio.core.TypographyRoles

/**
 * Specimen for the 15 Material 3 typography roles. Each row renders a
 * sample sentence in that style with the role name labelled. Tap or
 * long-press a row to open the typography editor for that role.
 *
 * Sample text is intentionally generic ("The quick brown fox…" pangram)
 * so devs can compare proportions and weight across styles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TypographySpecimen(
    modifier: Modifier = Modifier,
    onPickRole: ((roleName: String) -> Unit)? = null,
) {
    val typo = MaterialTheme.typography
    Column(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TypographyRoles.all.forEach { role ->
            val style = role.read(typo)
            val tap = onPickRole?.let { { it(role.name) } }
            Row(
                modifier = (if (tap != null) Modifier.combinedClickable(onClick = tap, onLongClick = tap)
                    else Modifier)
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = role.name,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = SAMPLE,
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private const val SAMPLE = "The quick brown fox jumps over the lazy dog 0123"
