package com.mappo.ui.screen

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mappo.R
import com.mappo.steam.workshop.KvTag
import com.mappo.steam.workshop.Preview
import com.mappo.steam.workshop.Tag
import com.mappo.steam.workshop.WorkshopConfig
import com.mappo.ui.viewmodel.SteamConfigDetailState
import com.mappo.ui.viewmodel.SteamConfigDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SteamConfigDetailScreen(
    onBack: () -> Unit,
    viewModel: SteamConfigDetailViewModel = hiltViewModel(),
) {
    val state = viewModel.state

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (state) {
                            is SteamConfigDetailState.Found -> state.config.title
                            is SteamConfigDetailState.NotFound -> stringResource(R.string.steam_config_detail_title_not_found)
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.steam_back),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(modifier = Modifier.padding(contentPadding).fillMaxSize()) {
            when (state) {
                is SteamConfigDetailState.Found -> DetailBody(state.config)
                is SteamConfigDetailState.NotFound -> NotFoundBody(state.publishedFileId)
            }
        }
    }
}

@Composable
private fun DetailBody(config: WorkshopConfig) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HeaderSection(config) }
        item { StatsSection(config) }
        item { TagsSection(config.tags) }
        item { KvTagsSection(config.kvTags) }
        item { PreviewsSection(config.previews, config.previewUrl, config.imageUrl) }
        item { DescriptionSection(config) }
        item { FileSection(config) }
        item { MiscSection(config) }
    }
}

// ───────────────────────────── Sections ──────────────────────────────

@Composable
private fun HeaderSection(config: WorkshopConfig) {
    Section(R.string.steam_config_section_header) {
        Text(
            text = stringResource(R.string.steam_config_field_app_name, config.appName.ifEmpty { "—" }, config.consumerAppId),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.steam_config_field_author, config.creatorSteamId64.toString()),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.steam_config_field_created, relative(config.timeCreatedEpochSec)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.steam_config_field_updated, relative(config.timeUpdatedEpochSec)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.steam_config_field_published_file_id, config.publishedFileId),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatsSection(config: WorkshopConfig) {
    Section(R.string.steam_config_section_stats) {
        StatRow(R.string.steam_config_stat_score, "%.3f".format(config.voteScore))
        StatRow(R.string.steam_config_stat_votes_up, config.votesUp.toString())
        StatRow(R.string.steam_config_stat_votes_down, config.votesDown.toString())
        StatRow(R.string.steam_config_stat_subscriptions, config.subscriptions.toString())
        StatRow(R.string.steam_config_stat_lifetime_subscriptions, config.lifetimeSubscriptions.toString())
        StatRow(R.string.steam_config_stat_favorited, config.favorited.toString())
        StatRow(R.string.steam_config_stat_lifetime_favorited, config.lifetimeFavorited.toString())
        StatRow(R.string.steam_config_stat_followers, config.followers.toString())
        StatRow(R.string.steam_config_stat_lifetime_followers, config.lifetimeFollowers.toString())
        StatRow(R.string.steam_config_stat_views, config.views.toString())
        StatRow(R.string.steam_config_stat_comments, config.numCommentsPublic.toString())
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(tags: List<Tag>) {
    Section(R.string.steam_config_section_tags) {
        if (tags.isEmpty()) {
            Text(
                stringResource(R.string.steam_config_empty_tags),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tags.forEach { tag ->
                AssistChip(
                    onClick = { /* tags non-interactive in POC */ },
                    label = { Text(tag.displayName.ifEmpty { tag.tag }) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
}

@Composable
private fun KvTagsSection(kvTags: List<KvTag>) {
    Section(R.string.steam_config_section_kvtags) {
        if (kvTags.isEmpty()) {
            Text(
                stringResource(R.string.steam_config_empty_kvtags),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }
        kvTags.forEach { kv ->
            Text(
                text = "${kv.key} = ${kv.value}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PreviewsSection(previews: List<Preview>, previewUrl: String?, imageUrl: String?) {
    Section(R.string.steam_config_section_previews) {
        if (!previewUrl.isNullOrEmpty()) {
            Text(
                stringResource(R.string.steam_config_field_preview_url),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = previewUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (!imageUrl.isNullOrEmpty()) {
            Text(
                stringResource(R.string.steam_config_field_image_url),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = imageUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (previews.isEmpty() && previewUrl.isNullOrEmpty() && imageUrl.isNullOrEmpty()) {
            Text(
                stringResource(R.string.steam_config_empty_previews),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Section
        }
        previews.forEach { p ->
            Text(
                text = stringResource(R.string.steam_config_preview_row, p.sortOrder, p.previewType, p.url),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun DescriptionSection(config: WorkshopConfig) {
    Section(R.string.steam_config_section_description) {
        if (config.shortDescription.isNotEmpty()) {
            Text(
                stringResource(R.string.steam_config_field_short_description),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(config.shortDescription, style = MaterialTheme.typography.bodyMedium)
        }
        if (config.fileDescription.isNotEmpty()) {
            Text(
                stringResource(R.string.steam_config_field_file_description),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(config.fileDescription, style = MaterialTheme.typography.bodyMedium)
        }
        if (config.shortDescription.isEmpty() && config.fileDescription.isEmpty()) {
            Text(
                stringResource(R.string.steam_config_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileSection(config: WorkshopConfig) {
    val context = LocalContext.current
    Section(R.string.steam_config_section_file) {
        StatRow(R.string.steam_config_file_name, config.fileName.ifEmpty { "—" })
        StatRow(R.string.steam_config_file_size, Formatter.formatShortFileSize(context, config.fileSizeBytes))
        StatRow(R.string.steam_config_file_hcontent, config.hcontentFile.toString())
        if (config.fileUrl.isNotEmpty()) {
            Text(
                stringResource(R.string.steam_config_file_url),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = config.fileUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        } else {
            Text(
                stringResource(R.string.steam_config_file_no_url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MiscSection(config: WorkshopConfig) {
    Section(R.string.steam_config_section_misc) {
        StatRow(R.string.steam_config_misc_visibility, visibilityLabel(config.visibility))
        StatRow(R.string.steam_config_misc_language, config.language.toString())
        StatRow(R.string.steam_config_misc_flags, "0x%08X".format(config.flags))
        StatRow(R.string.steam_config_misc_revision_change, config.revisionChangeNumber.toString())
        StatRow(R.string.steam_config_misc_num_children, config.numChildren.toString())
        if (config.maybeInappropriateSex) {
            Text(stringResource(R.string.steam_config_misc_inappropriate_sex), style = MaterialTheme.typography.bodyMedium)
        }
        if (config.maybeInappropriateViolence) {
            Text(stringResource(R.string.steam_config_misc_inappropriate_violence), style = MaterialTheme.typography.bodyMedium)
        }
        if (config.spoilerTag) {
            Text(stringResource(R.string.steam_config_misc_spoiler), style = MaterialTheme.typography.bodyMedium)
        }
        if (config.metadata.isNotEmpty()) {
            Text(
                stringResource(R.string.steam_config_misc_metadata),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(config.metadata, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

// ───────────────────────────── Helpers ───────────────────────────────

@Composable
private fun Section(titleRes: Int, content: @Composable () -> Unit) {
    // surfaceContainer — section card per M3 standards.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun StatRow(labelRes: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun NotFoundBody(publishedFileId: Long) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.steam_config_not_found_body, publishedFileId),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun visibilityLabel(v: Int): String = when (v) {
    0 -> stringResource(R.string.steam_config_visibility_public)
    1 -> stringResource(R.string.steam_config_visibility_friends_only)
    2 -> stringResource(R.string.steam_config_visibility_private)
    else -> v.toString()
}

private fun relative(epochSec: Int): CharSequence =
    if (epochSec <= 0) "—"
    else DateUtils.getRelativeTimeSpanString(
        epochSec.toLong() * 1000L,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    )
