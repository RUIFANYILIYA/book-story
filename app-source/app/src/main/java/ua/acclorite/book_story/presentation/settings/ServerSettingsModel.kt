/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.acclorite.book_story.R
import ua.acclorite.book_story.data.settings.SettingsManager
import ua.acclorite.book_story.domain.model.remote.RemoteBook
import ua.acclorite.book_story.domain.model.remote.RemoteDownload
import ua.acclorite.book_story.domain.repository.AccountProblem
import ua.acclorite.book_story.domain.repository.AccountRepository
import ua.acclorite.book_story.domain.repository.ServerAccount
import ua.acclorite.book_story.domain.repository.ShelfRepository
import javax.inject.Inject

@Immutable
data class ServerSettingsState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val account: ServerAccount? = null,
    val shelf: List<RemoteBook> = emptyList(),
    /** server book id -> what its download is doing right now. Absent means idle. */
    val downloads: Map<String, RemoteDownload> = emptyMap(),
    val syncing: Boolean = false,
    /** A string resource so the wording follows the app language, not the server's. */
    val messageRes: Int? = null,
    /** Whatever the server said, shown under the localized headline. */
    val messageDetail: String? = null
)

@HiltViewModel
class ServerSettingsModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val shelfRepository: ShelfRepository,
    private val settings: SettingsManager
) : ViewModel() {

    private val _state = MutableStateFlow(ServerSettingsState())
    val state = _state.asStateFlow()

    init {
        _state.update { it.copy(baseUrl = settings.serverUrl.lastValue) }
        viewModelScope.launch {
            combine(
                shelfRepository.remoteBooks,
                shelfRepository.downloads,
                shelfRepository.syncing
            ) { shelf, downloads, syncing -> Triple(shelf, downloads, syncing) }
                .collect { (shelf, downloads, syncing) ->
                    _state.update {
                        it.copy(
                            shelf = shelf,
                            downloads = downloads,
                            syncing = syncing
                        )
                    }
                }
        }
        if (accountRepository.signedIn()) {
            loadAccount()
            syncShelf()
        }
    }

    fun onUrlChange(url: String) {
        val trimmed = url.trim()
        settings.serverUrl.update(trimmed)
        _state.update { it.copy(baseUrl = trimmed) }
    }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value) }

    fun checkServer() = call(R.string.server_ok) {
        accountRepository.checkServer(state.value.baseUrl).map { }
    }

    fun login() = call(R.string.server_signed_in) {
        accountRepository.login(state.value.baseUrl, state.value.username, state.value.password)
    }

    fun logout() {
        viewModelScope.launch {
            accountRepository.logout()
            // The cached covers belong to that account's server, not to this device.
            shelfRepository.forgetCovers()
            settings.serverAccountLabel.update("")
            _state.update {
                it.copy(account = null, password = "", messageRes = R.string.server_signed_out)
            }
        }
    }

    private fun loadAccount() {
        viewModelScope.launch {
            accountRepository.currentAccount().onSuccess { account ->
                _state.update { it.copy(account = account) }
                settings.serverAccountLabel.update(account.displayName)
            }.onFailure { error ->
                // A stale token after a server reinstall is routine, not something to nag about:
                // the sign-in form is right there on this screen.
                _state.update { it.copy(account = null) }
                settings.serverAccountLabel.update("")
            }
        }
    }

    fun syncShelf() {
        viewModelScope.launch {
            shelfRepository.sync().onFailure { report(it) }
        }
    }

    fun download(book: RemoteBook) = shelfRepository.download(book.id)

    private fun call(successRes: Int, block: suspend () -> Result<*>) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, messageRes = null, messageDetail = null) }
            val result = block()
            val account = result.getOrNull() as? ServerAccount

            when {
                account != null -> {
                    settings.serverAccountLabel.update(account.displayName)
                    syncShelf()
                    _state.update {
                        it.copy(
                            busy = false,
                            account = account,
                            password = "",
                            messageRes = successRes
                        )
                    }
                }

                result.isSuccess -> _state.update {
                    it.copy(busy = false, messageRes = successRes)
                }

                else -> {
                    _state.update { it.copy(busy = false) }
                    report(result.exceptionOrNull())
                }
            }
        }
    }

    private fun report(error: Throwable?) {
        when (error) {
            is AccountProblem -> _state.update {
                it.copy(messageRes = resourceFor(error.code), messageDetail = error.message)
            }

            else -> _state.update {
                // Without the reason on screen this failure is undiagnosable on a phone, where
                // there is no console to read.
                it.copy(
                    messageRes = R.string.server_failed,
                    messageDetail = error?.message?.take(240)
                )
            }
        }
    }

    private fun resourceFor(code: String) = when (code) {
        "invalid_credentials" -> R.string.server_error_credentials
        "account_locked" -> R.string.server_error_locked
        "unauthorized", "invalid_refresh", "token_reused" -> R.string.server_error_signed_out_now
        "bad_address", "no_server" -> R.string.server_error_address
        "unreachable" -> R.string.server_error_unreachable
        "upgrade_required" -> R.string.server_error_upgrade
        else -> R.string.server_failed
    }
}
