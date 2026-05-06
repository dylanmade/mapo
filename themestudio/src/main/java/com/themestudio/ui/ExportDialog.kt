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
import com.themestudio.core.ShapeOverrides
import com.themestudio.core.ShapeRoles
import com.themestudio.core.ThemeOverrides
import com.themestudio.core.TypographyOverrides
import com.themestudio.core.TypographyRoles

/**
 * Modal dialog showing the current overrides as copy-pasteable Kotlin
 * snippets. Emits separate blocks for color schemes, typography, and shapes
 * — only including roles that have at least one override.
 *
 * The clipboard action is the primary intent: tweak in the editor, hit
 * "Copy", paste into your `Color.kt` / `Theme.kt` / `Type.kt` / `Shape.kt`
 * to commit the values.
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
                    "Only the roles you've changed appear below. Paste into your theme files.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 360.dp)
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
    val parts = listOfNotNull(
        renderColorScheme("light", "lightColorScheme", overrides.colors.light),
        renderColorScheme("dark", "darkColorScheme", overrides.colors.dark),
        renderTypography(overrides.typography),
        renderShapes(overrides.shapes),
    )
    return if (parts.isEmpty()) "// No overrides yet — tweak some values first."
    else parts.joinToString("\n\n")
}

private fun renderColorScheme(label: String, fnName: String, variant: ColorOverrides): String? {
    val rows = ColorRoles.all.mapNotNull { role: ColorRole ->
        val v: Color? = role.readOverride(variant)
        v?.let { "    ${role.name} = ${it.toKotlinLiteral()}," }
    }
    if (rows.isEmpty()) return null
    return buildString {
        append("// $label color overrides\n")
        append("$fnName(\n")
        rows.forEach { append(it).append('\n') }
        append(")")
    }
}

private fun renderTypography(typo: TypographyOverrides): String? {
    val rows = TypographyRoles.all.mapNotNull { role ->
        val o = role.readOverride(typo)
        if (o.fontSize == null && o.fontWeight == null && o.letterSpacing == null) return@mapNotNull null
        val fields = mutableListOf<String>()
        o.fontSize?.let { fields += "fontSize = ${it.value.toInt()}.sp" }
        o.fontWeight?.let { fields += "fontWeight = FontWeight(${it.weight})" }
        o.letterSpacing?.let { fields += "letterSpacing = %.2f.sp".format(it.value) }
        "    ${role.name} = ${role.name}.copy(${fields.joinToString(", ")}),"
    }
    val familyLines = buildList {
        typo.displayFontFamilyName?.let { add("// Display family: ${'"'}$it${'"'} (Google Font)") }
        typo.bodyFontFamilyName?.let { add("// Body family:    ${'"'}$it${'"'} (Google Font)") }
    }
    if (rows.isEmpty() && familyLines.isEmpty()) return null
    return buildString {
        append("// typography overrides\n")
        familyLines.forEach { append(it).append('\n') }
        if (rows.isNotEmpty()) {
            append("Typography(\n")
            rows.forEach { append(it).append('\n') }
            append(")")
        }
    }
}

private fun renderShapes(shapes: ShapeOverrides): String? {
    val rows = ShapeRoles.all.mapNotNull { role ->
        val v = role.readOverride(shapes)
        v?.let { "    ${role.name} = RoundedCornerShape(${it.value.toInt()}.dp)," }
    }
    if (rows.isEmpty()) return null
    return buildString {
        append("// shape overrides\n")
        append("Shapes(\n")
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
