package io.github.aedev.flow.ui.tv.screens.settings

import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.screens.update.UpdateStage
import io.github.aedev.flow.ui.screens.update.UpdateUiState
import io.github.aedev.flow.ui.screens.update.UpdateViewModel
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvToggleRow

private const val PERCENT = 100

/** Automatic updates, and where the newest release stands with the action it offers next. */
@Composable
internal fun TvUpdateSettings(modifier: Modifier = Modifier) {
    val activity = LocalContext.current as ComponentActivity
    val updates: TvUpdatesViewModel = hiltViewModel(activity)
    if (!updates.isAvailable) return
    val update: UpdateViewModel = hiltViewModel()
    val automatic by updates.automatic.collectAsStateWithLifecycle()
    val state by update.state.collectAsStateWithLifecycle()
    // Coming back from Android's install-apps setting or its installer changes what can happen next.
    LifecycleResumeEffect(update) {
        update.onResume()
        onPauseOrDispose { }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvToggleRow(
            label = stringResource(R.string.tv_update_automatic),
            supportingText = stringResource(R.string.tv_update_automatic_subtitle),
            checked = automatic,
            onCheckedChange = updates::setAutomatic,
        )
        TvNavRow(
            label = updateLabel(state),
            supportingText = updateHint(state),
            value = (state.stage as? UpdateStage.Downloading)?.progress?.let { "${(it * PERCENT).toInt()}%" },
            leadingIcon = Icons.Outlined.SystemUpdate,
            onClick = {
                when {
                    state.loading -> {
                        Unit
                    }

                    state.release == null -> {
                        update.check()
                    }

                    state.stage == UpdateStage.NeedsPermission -> {
                        // Some TV builds have no screen for the install-apps grant; the installer asks instead.
                        try {
                            activity.startActivity(update.installPermissionIntent())
                        } catch (_: ActivityNotFoundException) {
                            update.installNow()
                        }
                    }

                    else -> {
                        update.primaryAction()
                    }
                }
            },
        )
    }
}

@Composable
private fun updateLabel(state: UpdateUiState): String {
    val version = state.release?.version
    return when {
        state.loading -> {
            stringResource(R.string.tv_update_checking)
        }

        version == null -> {
            stringResource(if (state.checkFailed) R.string.tv_update_check_failed else R.string.tv_update_current)
        }

        else -> {
            when (state.stage) {
                UpdateStage.Available -> stringResource(R.string.tv_update_download, version)
                is UpdateStage.Downloading -> stringResource(R.string.tv_update_downloading, version)
                UpdateStage.Verifying -> stringResource(R.string.tv_update_verifying, version)
                UpdateStage.NeedsPermission -> stringResource(R.string.tv_update_permission)
                UpdateStage.Ready -> stringResource(R.string.tv_update_install, version)
                UpdateStage.Installing -> stringResource(R.string.tv_update_installing, version)
                is UpdateStage.Failed -> stringResource(R.string.tv_update_failed, version)
            }
        }
    }
}

@Composable
private fun updateHint(state: UpdateUiState): String? =
    when {
        state.loading -> null
        state.release == null -> stringResource(R.string.tv_update_check_again)
        state.stage == UpdateStage.NeedsPermission -> stringResource(R.string.tv_update_permission_subtitle)
        state.stage is UpdateStage.Failed -> stringResource(R.string.tv_update_try_again)
        else -> null
    }
