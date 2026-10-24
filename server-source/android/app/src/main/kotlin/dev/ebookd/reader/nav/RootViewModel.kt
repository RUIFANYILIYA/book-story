package dev.ebookd.reader.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ebookd.core.data.CapabilityStore
import dev.ebookd.core.data.CoverRepository
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.data.SyncRepository
import dev.ebookd.core.security.TokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Decides which screen the app opens on: configuration, sign-in, or the shelf.
 *
 * The three-way choice is why this is not a boolean. A configured address with no session (the
 * common case after a sign-out) must not land on the address screen again — that would make the
 * app look like it forgot the server — and an unconfigured app must not show a sign-in form that
 * cannot possibly work.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val tokens: TokenStore,
    private val sync: SyncRepository,
    private val capabilities: CapabilityStore,
    private val covers: CoverRepository,
) : ViewModel() {

    sealed interface Start {
        data object Loading : Start
        data class Ready(val route: String) : Start
    }

    val start: StateFlow<Start> = flow {
        val address = settings.baseUrl.first()
        // Reading the store touches the Keystore, which must not happen on the main thread.
        val hasSession = withContext(Dispatchers.IO) { tokens.refreshToken() != null }
        val route = when {
            address == null -> Routes.SERVER
            !hasSession -> Routes.LOGIN
            else -> Routes.SHELF
        }
        emit(Start.Ready(route))
        // The app opening is one of the two moments a sync is due — the other is a position being
        // written — and it is the one that catches up on whatever was read on another device since
        // this phone was last used. Only when there is a session to sync with: asking while signed
        // out would put a failure on the account screen that the reader cannot act on.
        if (route == Routes.SHELF) {
            // Run here rather than queued as work: a worker request made while an earlier attempt is
            // waiting out its backoff is absorbed by `KEEP` and does nothing, which on the emulator
            // left the first cold start after a failed sync showing a stale shelf for minutes.
            // Off the main thread because WorkManager's first `getInstance` waits for its own
            // initialization, and because this is a network round trip.
            withContext(Dispatchers.Default) {
                // Which covers the reader replaced on this device, read once so the shelf never
                // touches the disk while composing a card.
                covers.refresh()
                // First the answer to "what is this session allowed to do", because the shelf draws
                // its batch menu from it; then the exchange itself.
                capabilities.refresh()
                sync.syncNow()
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Start.Loading)
}
