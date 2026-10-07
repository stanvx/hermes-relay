package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.viewmodel.InteractionMode

const val VOICE_OPTIONS_SHEET_TEST_TAG = "voiceOptionsSheet"

/**
 * Voice session options, revealed on demand. The live voice surfaces keep
 * only state, the mic, options and close; talk mode, the route, the view
 * switch, pop-out and settings live here (progressive disclosure, in the
 * style of the composer's "+" sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceOptionsSheet(
    engineMode: String?,
    interactionMode: InteractionMode,
    engineText: String,
    profileText: String,
    providerText: String,
    focusMode: Boolean,
    systemOverlayAvailable: Boolean,
    onModeChange: (InteractionMode) -> Unit,
    onFocusModeChange: (Boolean) -> Unit,
    onOverlayRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        fun pick(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .testTag(VOICE_OPTIONS_SHEET_TEST_TAG),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.voice_overlay_voice),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                VoiceRouteSummary(
                    engine = engineText,
                    profile = profileText,
                    provider = providerText,
                )
                if (engineMode == "gpt_live") {
                    // GPT-Live owns turn-taking; the saved talk mode does not apply.
                    Text(
                        text = stringResource(R.string.voice_overlay_gpt_live_mode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val modes = InteractionMode.values()
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voiceOptionsModeRow"),
                    ) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = interactionMode == mode,
                                onClick = { onModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                label = {
                                    Text(
                                        text = mode.optionLabel(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OptionRow(
                icon = if (focusMode) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.CenterFocusStrong,
                label = if (focusMode) {
                    stringResource(R.string.voice_overlay_conversation)
                } else {
                    stringResource(R.string.voice_overlay_focus)
                },
                onClick = pick { onFocusModeChange(!focusMode) },
                modifier = Modifier.testTag("voiceOptionsFocusToggle"),
            )
            if (systemOverlayAvailable) {
                OptionRow(
                    icon = Icons.Outlined.PictureInPictureAlt,
                    label = stringResource(R.string.voice_options_pop_out),
                    onClick = pick(onOverlayRequest),
                    modifier = Modifier.testTag("voiceOptionsPopOut"),
                )
            }
            OptionRow(
                icon = Icons.Outlined.Settings,
                label = stringResource(R.string.voice_overlay_settings_cd),
                onClick = pick(onOpenSettings),
                modifier = Modifier.testTag("voiceOptionsSettings"),
            )
        }
    }
}

@Composable
private fun InteractionMode.optionLabel(): String = when (this) {
    InteractionMode.TapToTalk -> stringResource(R.string.voice_overlay_tap)
    InteractionMode.HoldToTalk -> stringResource(R.string.voice_overlay_hold)
    InteractionMode.Continuous -> stringResource(R.string.voice_overlay_auto)
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
    )
}
