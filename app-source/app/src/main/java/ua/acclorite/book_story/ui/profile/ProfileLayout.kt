/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ua.acclorite.book_story.R
import ua.acclorite.book_story.presentation.profile.ProfileState
import ua.acclorite.book_story.ui.common.helpers.LocalSettings

/**
 * The "you" tab. Account facts come from the server, reading time from this device, and the one
 * action that only lives here is the password change.
 */
@Composable
fun ProfileLayout(
    state: ProfileState,
    onCurrentPasswordChange: (String) -> Unit,
    onNewPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
    onChangePassword: () -> Unit,
    onSyncShelves: () -> Unit,
    onCheckBooks: () -> Unit,
    onDownloadChanges: () -> Unit,
    onDismissChanges: () -> Unit,
    onSignOut: () -> Unit,
    onMessageShown: () -> Unit
) {
    val settings = LocalSettings.current
    val readingTotal = settings.readingTimeTotalMs.value
    val readingToday = settings.readingTimeTodayMs.value

    state.message?.let { message ->
        LaunchedEffect(message) {
            delay(4_000)
            onMessageShown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(id = R.string.profile_title),
            style = MaterialTheme.typography.headlineSmall
        )

        /* ------ Account ----------------------------- */
        SectionTitle(R.string.profile_account_section)
        if (state.signedIn && state.account != null) {
            Fact(R.string.profile_server, state.serverUrl)
            Fact(R.string.profile_username, state.account.username)
            Fact(R.string.profile_display_name, state.account.displayName)
            Fact(R.string.profile_role, state.account.role)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSignOut) {
                Text(stringResource(id = R.string.profile_sign_out))
            }
        } else {
            Text(
                text = stringResource(id = R.string.profile_not_signed_in),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        /* ------ Server shelves ---------------------- */
        if (state.signedIn && state.account != null) {
            SectionTitle(R.string.profile_shelves_section)
            Text(
                text = stringResource(id = R.string.profile_shelves_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onSyncShelves,
                enabled = !state.syncingShelves,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        id = if (state.syncingShelves) R.string.profile_shelves_syncing
                        else R.string.profile_shelves_sync
                    )
                )
            }
            state.shelfSync?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (result.booksMatched > 0) {
                        stringResource(
                            id = R.string.profile_shelves_sync_done_matched,
                            result.serverShelves,
                            result.shelvesCreated,
                            result.booksFiled,
                            result.booksMatched
                        )
                    } else {
                        stringResource(
                            id = R.string.profile_shelves_sync_done,
                            result.serverShelves,
                            result.shelvesCreated,
                            result.booksFiled
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.shelfSyncError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            /* ------ New books on the server ----------- */
            SectionTitle(R.string.profile_books_section)
            Text(
                text = stringResource(id = R.string.profile_books_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onCheckBooks,
                enabled = !state.checkingBooks,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        id = if (state.checkingBooks) R.string.profile_books_checking
                        else R.string.profile_books_check
                    )
                )
            }
            if (state.booksUpToDate) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.profile_books_up_to_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.bookCheckError?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        state.serverChanges?.let { changes ->
            ServerChangesDialog(
                changes = changes,
                onDownload = onDownloadChanges,
                onDismiss = onDismissChanges
            )
        }

        /* ------ Reading time ------------------------ */
        SectionTitle(R.string.profile_reading_section)
        Fact(R.string.profile_reading_today, formatDuration(readingToday))
        Fact(R.string.profile_reading_total, formatDuration(readingTotal))

        /* ------ Password ---------------------------- */
        SectionTitle(R.string.profile_password_section)
        PasswordField(
            value = state.currentPassword,
            label = stringResource(id = R.string.profile_password_current),
            onValueChange = onCurrentPasswordChange
        )
        Spacer(Modifier.height(10.dp))
        PasswordField(
            value = state.newPassword,
            label = stringResource(id = R.string.profile_password_new),
            onValueChange = onNewPasswordChange
        )
        Spacer(Modifier.height(10.dp))
        PasswordField(
            value = state.confirmPassword,
            label = stringResource(id = R.string.profile_password_confirm),
            onValueChange = onConfirmPasswordChange
        )
        Spacer(Modifier.height(14.dp))
        // Above the button on purpose: below it, the answer to "did that work?" lands underneath
        // the button and the bottom bar, where nobody looks after tapping.
        state.message?.let { message ->
            Text(
                text = message.textRes?.let { stringResource(id = it) }
                    ?: message.detail
                    ?: stringResource(id = R.string.profile_password_changed),
                style = MaterialTheme.typography.bodySmall,
                color = if (message.changed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = onChangePassword,
            enabled = !state.busy && state.signedIn
        ) {
            Text(stringResource(id = R.string.profile_password_submit))
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SectionTitle(textRes: Int) {
    Spacer(Modifier.height(26.dp))
    Text(
        text = stringResource(id = textRes),
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(8.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Fact(labelRes: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PasswordField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Minutes are the smallest unit worth showing: a reader wants "3 小时 12 分", not milliseconds. */
@Composable
private fun formatDuration(millis: Long): String {
    val hours = millis / 3_600_000
    val minutes = (millis % 3_600_000) / 60_000
    return when {
        hours > 0 -> "${hours}${stringResource(id = R.string.profile_reading_hours)}" +
            "${minutes}${stringResource(id = R.string.profile_reading_minutes)}"
        minutes > 0 -> "${minutes}${stringResource(id = R.string.profile_reading_minutes)}"
        else -> stringResource(id = R.string.profile_reading_less)
    }
}
