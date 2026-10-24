/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.settings

import android.os.Parcelable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.parcelize.Parcelize
import ua.acclorite.book_story.R
import ua.acclorite.book_story.domain.model.remote.RemoteDownloadPhase
import ua.acclorite.book_story.presentation.navigator.Screen
import ua.acclorite.book_story.ui.common.components.common.LazyColumnWithScrollbar
import ua.acclorite.book_story.ui.navigator.LocalNavigator

@Parcelize
object ServerSettingsScreen : Screen, Parcelable {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val model = hiltViewModel<ServerSettingsModel>()
        val state by model.state.collectAsStateWithLifecycle()

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 0.dp),
            topBar = {
                LargeTopAppBar(
                    title = { Text(text = stringResource(id = R.string.server_settings)) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null
                            )
                        }
                    },
                    scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
                )
            }
        ) { paddingValues ->
            LazyColumnWithScrollbar(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
            ) {
                item {
                    // A real input field, not a line of text that happens to be tappable: the
                    // address is the one thing on this screen a first-time user must find, and a
                    // borderless row reads as a display value rather than something to fill in.
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = model::onUrlChange,
                        label = { Text(text = stringResource(id = R.string.server_url_option)) },
                        placeholder = {
                            Text(text = stringResource(id = R.string.server_url_placeholder))
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = null
                            )
                        },
                        singleLine = true,
                        enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )
                }

                item {
                    val account = state.account

                    if (account != null) {
                        Text(
                            text = stringResource(
                                id = R.string.server_signed_in_as,
                                account.displayName,
                                account.role
                            ),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = { model.logout() }) {
                            Text(text = stringResource(id = R.string.server_logout_action))
                        }
                    } else {
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = model::onUsernameChange,
                            label = { Text(text = stringResource(id = R.string.server_username_label)) },
                            singleLine = true,
                            enabled = !state.busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = model::onPasswordChange,
                            label = { Text(text = stringResource(id = R.string.server_password_label)) },
                            singleLine = true,
                            enabled = !state.busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                        Button(
                            onClick = { model.login() },
                            enabled = !state.busy
                        ) {
                            Text(text = stringResource(id = R.string.server_login_action))
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { model.checkServer() },
                            enabled = !state.busy
                        ) {
                            Text(text = stringResource(id = R.string.server_check_action))
                        }
                    }
                }

                if (state.account != null) {
                    item {
                        Spacer(modifier = Modifier.height(28.dp))
                        Text(
                            text = stringResource(id = R.string.server_shelf_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(id = R.string.server_shelf_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Bulk download lives on the shelf itself; this page is only for one at a time.
                        if (state.downloads.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    id = R.string.server_download_in_queue,
                                    state.downloads.size
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (state.shelf.isEmpty() && !state.syncing) {
                            Text(
                                text = stringResource(id = R.string.server_shelf_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }

                    items(state.shelf.size) { index ->
                        val book = state.shelf[index]
                        val download = state.downloads[book.id]

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = book.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = buildString {
                                        append(book.author.ifBlank { "—" })
                                        append(" · ")
                                        append(book.formats.joinToString("/"))
                                        append(" · ")
                                        append((book.sizeBytes / 1024L).toString())
                                        append(" KB")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            when {
                                download?.phase == RemoteDownloadPhase.FAILED -> Button(
                                    onClick = { model.download(book) }
                                ) {
                                    Text(text = stringResource(id = R.string.server_download_failed))
                                }

                                download != null -> Text(
                                    text = if (download.phase == RemoteDownloadPhase.RUNNING) {
                                        stringResource(
                                            id = R.string.server_downloading,
                                            (download.progress * 100f).toInt()
                                        )
                                    } else {
                                        stringResource(id = R.string.server_download_queued)
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )

                                book.inLibrary -> Text(
                                    text = stringResource(id = R.string.server_book_in_library),
                                    style = MaterialTheme.typography.bodySmall
                                )

                                else -> Button(onClick = { model.download(book) }) {
                                    Text(text = stringResource(id = R.string.server_download_action))
                                }
                            }
                        }
                    }
                }

                item {
                    if (state.busy) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    state.messageRes?.let { res ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(id = res),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        state.messageDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(id = R.string.server_http_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
