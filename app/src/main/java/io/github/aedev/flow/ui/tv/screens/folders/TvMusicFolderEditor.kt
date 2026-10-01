package io.github.aedev.flow.ui.tv.screens.folders

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.ui.screens.folders.FolderAccess
import io.github.aedev.flow.ui.screens.folders.MusicFolderEditor
import io.github.aedev.flow.ui.screens.folders.MusicFoldersViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvSearchField
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.components.TvToggleRow
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus

@Composable
internal fun TvMusicFolderEditor(
    draft: MusicFolderEditor,
    existing: Boolean,
    viewModel: MusicFoldersViewModel,
) {
    val source = draft.source
    LazyColumn(
        modifier = Modifier.fillMaxSize().tvInitialFocus(source.id, onFirstComposition = false).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(
            key = "header",
        ) { TvSectionHeader(stringResource(if (existing) R.string.music_folders_title else R.string.music_folders_add_smb)) }
        item(key = "back") { TvButton(stringResource(R.string.music_folders_editor_back), viewModel::closeEditor, enabled = !draft.busy) }
        if (source.kind == MusicFolderKind.LOCAL) {
            item(key = "name") { Text(source.name, style = MaterialTheme.typography.titleMedium) }
        } else {
            item(key = "name") {
                FolderField(source.name, R.string.music_folders_name) { viewModel.updateDraft { copy(source = source.copy(name = it)) } }
            }
            item(key = "server") {
                FolderField(source.host, R.string.music_folders_server) { viewModel.updateDraft { copy(source = source.copy(host = it)) } }
            }
            item(key = "port") {
                FolderField(draft.port, R.string.music_folders_port, KeyboardType.Number) { viewModel.updateDraft { copy(port = it) } }
            }
            item(key = "share") {
                FolderField(source.share, R.string.music_folders_share) { viewModel.updateDraft { copy(source = source.copy(share = it)) } }
            }
            item(key = "path") {
                FolderField(source.root, R.string.music_folders_path) { viewModel.updateDraft { copy(source = source.copy(root = it)) } }
            }
            item(key = "guest") {
                TvToggleRow(stringResource(R.string.music_folders_guest), source.guest, {
                    viewModel.updateDraft { copy(source = source.copy(guest = it)) }
                })
            }
            if (!source.guest) {
                item(key = "username") {
                    FolderField(
                        source.username,
                        R.string.music_folders_username,
                    ) { viewModel.updateDraft { copy(source = source.copy(username = it)) } }
                }
                item(key = "domain") {
                    FolderField(
                        source.domain,
                        R.string.music_folders_domain,
                    ) { viewModel.updateDraft { copy(source = source.copy(domain = it)) } }
                }
                item(key = "password") {
                    TvSearchField(
                        query = draft.password.orEmpty(),
                        onQueryChange = { viewModel.updateDraft { copy(password = it) } },
                        onSearch = {},
                        placeholder =
                            stringResource(
                                if (existing &&
                                    draft.password == null
                                ) {
                                    R.string.music_folders_password_keep
                                } else {
                                    R.string.music_folders_password
                                },
                            ),
                        label = stringResource(R.string.music_folders_password),
                        secure = true,
                        imeAction = ImeAction.Done,
                        leadingIcon = Icons.Outlined.Folder,
                    )
                }
            }
            item(key = "test") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val status =
                        when (draft.access) {
                            FolderAccess.SUCCESS -> R.string.music_folders_access_ok
                            FolderAccess.FAILED -> R.string.music_folders_access_failed
                            else -> null
                        }
                    status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                    draft.error?.let {
                        Text(
                            stringResource(it),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(
                            stringResource(
                                if (draft.access ==
                                    FolderAccess.RUNNING
                                ) {
                                    R.string.music_folders_testing
                                } else {
                                    R.string.music_folders_test
                                },
                            ),
                            viewModel::testAccess,
                            enabled = !draft.busy,
                        )
                        TvButton(stringResource(R.string.music_folders_save), viewModel::save, enabled = !draft.busy)
                    }
                }
            }
        }
        if (existing) {
            item(key = "remove") {
                TvButton(stringResource(R.string.music_folders_remove), { viewModel.remove(source) }, enabled = !draft.busy)
            }
        }
    }
}

@Composable
private fun FolderField(
    value: String,
    hint: Int,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    TvSearchField(
        value,
        onChange,
        onSearch = {
        },
        placeholder =
            stringResource(
                hint,
            ),
        label = stringResource(hint),
        leadingIcon = Icons.Outlined.Folder,
        keyboardType = keyboard,
        imeAction = ImeAction.Done,
    )
}
