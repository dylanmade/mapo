package com.mappo.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mappo.R
import com.mappo.service.shizuku.ShizukuState
import com.mappo.ui.viewmodel.ShizukuSetupViewModel

/**
 * **Brick G.** Production-quality Setup screen. Three sections:
 *
 *  1. **State header** — surfaceContainer-tonal callout naming the current
 *     Shizuku state in plain language.
 *  2. **Setup steps** — three-row checklist (Install / Start / Grant). The
 *     current step shows a primary CTA; finished steps show a check; pending
 *     steps show an outlined circle. Rows use the "settings" list-row
 *     treatment from the M3 doctrine memo — leading icon + headline +
 *     supporting subtext + trailing action.
 *  3. **Troubleshooting** — tip-style ListItems for common gotchas (state lag,
 *     post-reboot re-launch, digital-only fallback).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuSetupScreen(
    onBack: () -> Unit,
    viewModel: ShizukuSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shizuku_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.shizuku_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // ── State header (surfaceContainer role: callout) ────────────────
            StateHeader(state)

            // ── Setup steps section ──────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.shizuku_setup_steps_section))
                SetupStepRow(
                    status = state.installStatus(),
                    headlineRes = R.string.shizuku_setup_step_install,
                    subtitleRes = R.string.shizuku_setup_step_install_subtitle,
                    ctaRes = R.string.shizuku_install_button,
                    onCta = viewModel::installShizuku,
                )
                SetupStepRow(
                    status = state.startStatus(),
                    headlineRes = R.string.shizuku_setup_step_start,
                    subtitleRes = R.string.shizuku_setup_step_start_subtitle,
                    ctaRes = R.string.shizuku_open_app_button,
                    onCta = viewModel::openShizukuApp,
                )
                SetupStepRow(
                    status = state.grantStatus(),
                    headlineRes = R.string.shizuku_setup_step_grant,
                    subtitleRes = R.string.shizuku_setup_step_grant_subtitle,
                    ctaRes = R.string.shizuku_grant_button,
                    onCta = viewModel::requestPermission,
                )
            }

            // ── Troubleshooting section ──────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.shizuku_setup_troubleshooting_section))
                TroubleshootingRow(stringResource(R.string.shizuku_setup_troubleshooting_state_changes))
                TroubleshootingRow(stringResource(R.string.shizuku_setup_troubleshooting_reboot))
                TroubleshootingRow(stringResource(R.string.shizuku_setup_troubleshooting_digital))
            }
        }
    }
}

/**
 * Surface-rooted state callout: surfaceContainer tonal role per the M3
 * standards memo. Houses the current Shizuku state and a one-line summary.
 */
@Composable
private fun StateHeader(state: ShizukuState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // surfaceContainer: callout / hero block on a Scaffold-rooted screen.
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(state.statusStringRes()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.shizuku_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    // titleSmall + onSurfaceVariant for the section header — matches the M3
    // typography memo (titleSmall for sections).
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * One row in the Setup checklist. Leading icon reflects [status]; the trailing
 * action button only appears when the step is `Current` — finished steps are
 * non-interactive (the check is the affordance) and future steps are
 * deliberately ungated to keep the flow linear.
 */
@Composable
private fun SetupStepRow(
    status: StepStatus,
    headlineRes: Int,
    subtitleRes: Int,
    ctaRes: Int,
    onCta: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = status.icon(),
                contentDescription = stringResource(status.contentDescriptionRes()),
                tint = status.tint(),
                modifier = Modifier.size(24.dp),
            )
        },
        headlineContent = {
            Text(
                text = stringResource(headlineRes),
                // bodyLarge for list-item primaries per the typography memo.
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { StepRowTrailing(status, ctaRes, onCta) },
        // Default ListItem container color tracks the Scaffold root —
        // surface. Override only the supporting-text color above.
        colors = ListItemDefaults.colors(),
    )
}

/**
 * Hoisted out of [SetupStepRow]'s `trailingContent =` slot. An inline
 * `if (...) { /* lambda */ } else null` confuses overload resolution because
 * the type system can't infer the `@Composable () -> Unit` shape for the
 * branch — leaving the entire ListItem call to bind to the wrong overload
 * and cascading "non-composable context" errors to siblings. Hoisting fixes
 * the type-inference path.
 */
@Composable
private fun StepRowTrailing(status: StepStatus, ctaRes: Int, onCta: () -> Unit) {
    if (status == StepStatus.Current) {
        Button(onClick = onCta) {
            Text(stringResource(ctaRes))
        }
    }
}

@Composable
private fun TroubleshootingRow(text: String) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        headlineContent = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
}

private enum class StepStatus { Done, Current, Pending }

private fun StepStatus.icon(): ImageVector = when (this) {
    StepStatus.Done -> Icons.Default.CheckCircle
    StepStatus.Current -> Icons.AutoMirrored.Filled.ArrowForward
    StepStatus.Pending -> Icons.Outlined.RadioButtonUnchecked
}

@Composable
private fun StepStatus.tint() = when (this) {
    StepStatus.Done -> MaterialTheme.colorScheme.primary
    StepStatus.Current -> MaterialTheme.colorScheme.primary
    StepStatus.Pending -> MaterialTheme.colorScheme.outline
}

private fun StepStatus.contentDescriptionRes(): Int = when (this) {
    StepStatus.Done -> R.string.shizuku_setup_step_done
    StepStatus.Current -> R.string.shizuku_setup_step_current
    StepStatus.Pending -> R.string.shizuku_setup_step_pending
}

/**
 * Per-step status mapping. Each step is `Done` iff the user has progressed
 * past it, `Current` iff this is the next gap to close, `Pending` if a prior
 * step is still open.
 */
private fun ShizukuState.installStatus(): StepStatus = when (this) {
    ShizukuState.NotInstalled -> StepStatus.Current
    else -> StepStatus.Done
}

private fun ShizukuState.startStatus(): StepStatus = when (this) {
    ShizukuState.NotInstalled -> StepStatus.Pending
    ShizukuState.InstalledNotRunning -> StepStatus.Current
    else -> StepStatus.Done
}

private fun ShizukuState.grantStatus(): StepStatus = when (this) {
    ShizukuState.NotInstalled, ShizukuState.InstalledNotRunning -> StepStatus.Pending
    ShizukuState.RunningNotGranted -> StepStatus.Current
    ShizukuState.Granted -> StepStatus.Done
}

private fun ShizukuState.statusStringRes(): Int = when (this) {
    ShizukuState.NotInstalled -> R.string.shizuku_state_not_installed
    ShizukuState.InstalledNotRunning -> R.string.shizuku_state_installed_not_running
    ShizukuState.RunningNotGranted -> R.string.shizuku_state_running_not_granted
    ShizukuState.Granted -> R.string.shizuku_state_granted
}
