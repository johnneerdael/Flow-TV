package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.ui.screens.account.sharedAccountFeedsViewModel
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvToggleRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot

@Composable
fun TvAccountSettingsPane(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = sharedAccountFeedsViewModel()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val playHistory by viewModel.playHistoryEnabled.collectAsStateWithLifecycle()

    ProvideTvColumnPivot {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "status") { TvAccountStatusCard(session) }
            if (session == null || session?.expired == true) {
                item(key = "sign-in") {
                    TvNavRow(
                        label = stringResource(R.string.tv_account_sign_in_with_phone),
                        supportingText = stringResource(R.string.tv_account_sign_in_with_phone_summary),
                        leadingIcon = Icons.Outlined.QrCode2,
                        onClick = onSignIn,
                    )
                }
            }
            if (session?.expired == false) {
                item(key = "play-history") {
                    TvToggleRow(
                        label = stringResource(R.string.tv_account_play_history),
                        supportingText = stringResource(R.string.tv_account_play_history_summary),
                        checked = playHistory,
                        onCheckedChange = viewModel::setPlayHistoryEnabled,
                    )
                }
            }
            if (session != null) {
                item(key = "sign-out") {
                    TvNavRow(
                        label = stringResource(R.string.tv_account_sign_out),
                        leadingIcon = Icons.AutoMirrored.Outlined.Logout,
                        onClick = viewModel::signOut,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvAccountStatusCard(session: AccountSession?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text =
                        when {
                            session == null -> stringResource(R.string.tv_account_not_signed_in)
                            session.accountName != null -> session.accountName
                            else -> stringResource(R.string.tv_account_signed_in)
                        },
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text =
                        when {
                            session == null -> stringResource(R.string.tv_account_sign_in_with_phone_summary)
                            session.expired -> stringResource(R.string.tv_account_session_expired)
                            else -> stringResource(R.string.tv_account_signed_in_summary)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
