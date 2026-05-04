package com.themestudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.themestudio.core.ColorOverrides
import com.themestudio.core.ColorRole
import com.themestudio.core.ColorRoles
import com.themestudio.core.ThemeOverrides

/**
 * Modal dialog showing the current overrides as copy-pasteable Kotlin —
 * formatted as `lightColorScheme(...) {}` / `darkColorScheme(...) {}` snippets
 * with only overridden roles emitted.
 *
 * The clipboard action is the primary intent: tweak in the editor, hit
 * "Copy", paste into your `Color.kt` / `Theme.kt` to commit the values.
 */
@Composable
internal fun ExportDialog(
    overrides: ThemeOverrides,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val snippet = remember(overrides) { buildSnippet(overrides) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export overrides") },
        text = {
            Column {
                Text(
                    "Only roles you've changed appear below. Paste into your theme file.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 320.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = snippet,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, snippet)
                onDismiss()
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun buildSnippet(overrides: ThemeOverrides): String {
    val light = renderVariant("light", "lightColorScheme", overrides.light)
    val dark = renderVariant("dark", "darkColorScheme", overrides.dark)
    return when {
        light == null && dark == null -> "// No overrides yet — tweak some colors first."
        light != null && dark != null -> "$light\n\n$dark"
        light != null -> light
        else -> dark!!
    }
}

private fun renderVariant(
    label: String,
    fnName: String,
    variant: ColorOverrides,
): String? {
    val rows = ColorRoles.all.mapNotNull { role: ColorRole ->
        val v: Color? = role.readOverride(variant)
        v?.let { "    ${role.name} = ${it.toKotlinLiteral()}," }
    }
    if (rows.isEmpty()) return null
    return buildString {
        append("// $label overrides\n")
        append("$fnName(\n")
        rows.forEach { append(it).append('\n') }
        append(")")
    }
}

private fun Color.toKotlinLiteral(): String =
    "Color(0x%08X)".format(toArgb())

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("theme overrides", text))
}
