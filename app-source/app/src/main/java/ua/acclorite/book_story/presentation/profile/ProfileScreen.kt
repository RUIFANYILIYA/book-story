/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.profile

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.parcelize.Parcelize
import ua.acclorite.book_story.presentation.navigator.Screen
import ua.acclorite.book_story.ui.profile.ProfileLayout

@Parcelize
object ProfileScreen : Screen, Parcelable {

    @Composable
    override fun Content() {
        val model = hiltViewModel<ProfileModel>()
        val state by model.state.collectAsStateWithLifecycle()

        ProfileLayout(
            state = state,
            onCurrentPasswordChange = model::onCurrentPasswordChange,
            onNewPasswordChange = model::onNewPasswordChange,
            onConfirmPasswordChange = model::onConfirmPasswordChange,
            onChangePassword = model::changePassword,
            onSyncShelves = model::syncShelves,
            onCheckBooks = model::checkForNewBooks,
            onDownloadChanges = model::downloadBookChanges,
            onDismissChanges = model::dismissBookChanges,
            onSignOut = model::signOut,
            onMessageShown = model::dismissMessage
        )
    }
}
