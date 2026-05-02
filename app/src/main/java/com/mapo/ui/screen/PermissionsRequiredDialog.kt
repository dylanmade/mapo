package com.mapo.ui.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mapo.R
import com.mapo.service.InputAccessibilityService

/**
 * Locked startup dialog: stays up until both permissions are granted. There is no
 * dismiss/skip path because both permissions are required for Mapo to work.
 *
 * The composable evaluates permission state freshly on each recomposition; callers
 * should trigger recomposition on lifecycle resume so granting in Settings flows back.
 */
@Composable
fun PermissionsRequiredDialog(
    accessibilityGranted: Boolean,
    overlayGranted: Boolean
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        title = { Text(stringResource(R.string.permissions_required_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.permissions_required_intro),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(12.dp))
                PermissionRow(
                    label = stringResource(R.string.permission_accessibility_label),
                    description = stringResource(R.string.permission_accessibility_description),
                    granted = accessibilityGranted,
                    onGrant = { openAccessibilitySettings(context) }
                )
                Spacer(Modifier.height(8.dp))
                PermissionRow(
                    label = stringResource(R.string.permission_overlay_label),
                    description = stringResource(R.string.permission_overlay_description),
                    granted = overlayGranted,
                    onGrant = { openOverlaySettings(context) }
                )
            }
        },
        // No buttons — the dialog is locked until both permissions are granted via
        // the per-row Open Settings buttons. AlertDialog requires confirmButton to
        // be non-null so an empty composable is supplied.
        confirmButton = {}
    )
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                modifier = Modifier
            )
            Text(
                text = if (granted)
                    stringResource(R.string.permission_granted_indicator)
                else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!granted) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onGrant) {
                Text(stringResource(R.string.permission_grant_button), fontSize = 13.sp)
            }
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = ComponentName(context, InputAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}

fun isOverlayPermissionGranted(context: Context): Boolean =
    Settings.canDrawOverlays(context)

private fun openAccessibilitySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
}

private fun openOverlaySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )
    context.startActivity(intent)
}
