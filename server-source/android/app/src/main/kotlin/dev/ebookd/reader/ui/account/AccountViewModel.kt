package dev.ebookd.reader.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ebookd.core.data.BatchRepository
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.data.SyncRepository
import dev.ebookd.core.db.SyncStateEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What the server knows about this device, and one button that asks it again.
 *
 * The screen exists because sync is otherwise invisible: progress that has not left the phone looks
 * exactly like progress that has, right up until the reader picks up their other device. So the
 * numbers shown are the honest ones — the cursor is how far the server's history has been read, the
 * pending count is what is still owed, and a failure is printed rather than smoothed over.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val sync: SyncRepository,
    private val batch: BatchRepository,
    settings: SettingsStore,
) : ViewModel() {

    val baseUrl: StateFlow<String?> = settings.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val server: StateFlow<SyncStateEntity?> = sync.observeState().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Everything this device owes the server: positions in the outbox plus the shelf statuses queued
     * beside them. One number, because the reader's question is "is it safe to switch off?" and two
     * answers to it is one more than they can use.
     */
    val pending: StateFlow<Int> =
        combine(sync.observePending(), batch.observePendingCount()) { positions, statuses -> positions + statuses }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * Runs one exchange in the foreground and waits for it.
     *
     * Not queued as work: the reader is looking at the screen, and "synced just now" arriving
     * immediately after the tap is the whole point of the button. The worker stays the path for
     * everything happening in the background.
     */
    fun syncNow() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            sync.syncNow()
            _syncing.value = false
        }
    }
}
