/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.profile

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.settings.SettingsManager
import ua.acclorite.book_story.domain.repository.AccountRepository
import ua.acclorite.book_story.domain.repository.ServerAccount
import ua.acclorite.book_story.domain.repository.ServerChanges
import ua.acclorite.book_story.domain.repository.ShelfRepository
import ua.acclorite.book_story.domain.repository.ShelfSyncResult
import ua.acclorite.book_story.presentation.library.LibraryScreen
import ua.acclorite.book_story.presentation.settings.SettingsScreen

/** A one-shot note for the screen; the layout shows it and calls [ProfileModel.dismissMessage]. */
data class ProfileMessage(
    val changed: Boolean,
    @StringRes val textRes: Int? = null,
    /** The server's own words when it was the server that refused. */
    val detail: String? = null
)

data class ProfileState(
    val signedIn: Boolean = false,
    val serverUrl: String = "",
    val account: ServerAccount? = null,
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val busy: Boolean = false,
    val message: ProfileMessage? = null,

    /** Pulling the server's shelves: running, then either a summary or the reason it failed. */
    val syncingShelves: Boolean = false,
    val shelfSync: ShelfSyncResult? = null,
    val shelfSyncError: String? = null,

    /**
     * Checking the catalogue for books this device is missing or holds out of date. The result is
     * kept as [serverChanges] while the "take them?" dialog is open, and collapsed to
     * [booksUpToDate] once the reader has answered.
     */
    val checkingBooks: Boolean = false,
    val serverChanges: ServerChanges? = null,
    val booksUpToDate: Boolean = false,
    val bookCheckError: String? = null
)

/**
 * The "you" tab: who is signed in on this device, and the one account action that cannot be done
 * anywhere else in the app — changing the password. Reading time is displayed straight from the
 * settings, since it is accrued by the reader and nothing here owns it.
 */
@HiltViewModel
class ProfileModel @Inject constructor(
    private val account: AccountRepository,
    private val settings: SettingsManager,
    private val shelf: ShelfRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state = _state.asStateFlow()

    init {
        _state.update { it.copy(serverUrl = settings.serverUrl.lastValue) }
        if (account.signedIn()) loadAccount()
    }

    private fun loadAccount() {
        viewModelScope.launch {
            account.currentAccount()
                .onSuccess { me -> _state.update { it.copy(signedIn = true, account = me) } }
                // A stale token after a server reinstall is routine: show the signed-out state and
                // let the server screen take the new sign-in.
                .onFailure { _state.update { it.copy(signedIn = false, account = null) } }
        }
    }

    fun onCurrentPasswordChange(value: String) = _state.update { it.copy(currentPassword = value) }
    fun onNewPasswordChange(value: String) = _state.update { it.copy(newPassword = value) }
    fun onConfirmPasswordChange(value: String) = _state.update { it.copy(confirmPassword = value) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun changePassword() {
        val snapshot = _state.value
        validationError(snapshot)?.let { error ->
            _state.update { it.copy(message = ProfileMessage(changed = false, textRes = error)) }
            return
        }

        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            account.changePassword(snapshot.currentPassword, snapshot.newPassword)
                .onSuccess {
                    _state.update {
                        it.copy(
                            busy = false,
                            currentPassword = "",
                            newPassword = "",
                            confirmPassword = "",
                            message = ProfileMessage(changed = true)
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            message = ProfileMessage(changed = false, detail = error.message)
                        )
                    }
                }
        }
    }

    /**
     * Pulls the server's shelf tree and files this device's books into it.
     *
     * Lives on this tab because it is an account-scoped action: it needs the server and the
     * session, and it is the only place the app already talks about "the server" rather than about
     * the books.
     */
    fun syncShelves() {
        if (_state.value.syncingShelves) return

        _state.update { it.copy(syncingShelves = true, shelfSync = null, shelfSyncError = null) }
        viewModelScope.launch {
            shelf.syncShelves()
                .onSuccess { result ->
                    _state.update { it.copy(syncingShelves = false, shelfSync = result) }
                    // Both screens read their shelves from a snapshot taken elsewhere, so nudge
                    // them instead of asking the reader to leave the tab and come back.
                    LibraryScreen.refreshListChannel.trySend(0)
                    SettingsScreen.refreshCategoriesChannel.trySend(Unit)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(syncingShelves = false, shelfSyncError = error.message)
                    }
                }
        }
    }

    /**
     * Asks the server what this device is missing, then lets the reader decide.
     *
     * Nothing is downloaded by the check itself — it reads the catalogue, which already carries
     * every book's id and content timestamp, so the answer costs one paged request and no book
     * bytes. The decision is the reader's because "take them" on a metered connection with
     * forty missing books is not a question with an obvious answer.
     */
    fun checkForNewBooks() {
        if (_state.value.checkingBooks) return

        _state.update {
            it.copy(
                checkingBooks = true,
                serverChanges = null,
                booksUpToDate = false,
                bookCheckError = null
            )
        }
        viewModelScope.launch {
            shelf.checkServerChanges()
                .onSuccess { changes ->
                    _state.update {
                        it.copy(
                            checkingBooks = false,
                            // An empty result is not a dialog; it is a line of text.
                            serverChanges = changes.takeIf { c -> !c.isEmpty },
                            booksUpToDate = changes.isEmpty
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(checkingBooks = false, bookCheckError = error.message) }
                }
        }
    }

    /** "Not now." The result is dropped rather than remembered: the next check re-asks. */
    fun dismissBookChanges() = _state.update { it.copy(serverChanges = null) }

    /**
     * "Take them." The new books join the ordinary download queue and the updated ones re-download
     * in place, so the library screen's progress and retry affordances apply unchanged; the
     * library is nudged because the first of them may finish before the reader leaves this tab.
     */
    fun downloadBookChanges() {
        val changes = _state.value.serverChanges ?: return
        _state.update { it.copy(serverChanges = null) }
        shelf.downloadChanges(changes)
        LibraryScreen.refreshListChannel.trySend(0)
    }

    fun signOut() {
        viewModelScope.launch {
            account.logout()
            settings.serverAccountLabel.update("")
            _state.update {
                it.copy(signedIn = false, account = null, message = null)
            }
        }
    }

    /**
     * The same rules the server enforces, checked here so the two commonest mistakes (too short,
     * typed twice differently) do not need a round trip to be told about.
     */
    private fun validationError(state: ProfileState): Int? = when {
        state.currentPassword.isEmpty() -> R.string.profile_password_current_missing
        state.newPassword.length < MIN_PASSWORD_LENGTH -> R.string.profile_password_too_short
        state.newPassword == state.currentPassword -> R.string.profile_password_same
        state.newPassword != state.confirmPassword -> R.string.profile_password_mismatch
        else -> null
    }

    companion object {
        /** The server refuses anything shorter, and its message is not something a reader sees. */
        const val MIN_PASSWORD_LENGTH = 10
    }
}
