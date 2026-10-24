package dev.ebookd.reader.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.ebookd.core.data.OfflineRepository
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The download list: what the reader asked for, where each one stands, and what they can undo.
 *
 * There is no progress bar fed by a polling counter here. Progress belongs to the transfer, and the
 * transfer's truth is the temp file's length; the list shows the *state*, which is the part that has
 * to survive a restart and the part a reader acts on.
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val offline: OfflineRepository,
) : ViewModel() {

    val tasks = offline.observeTasks()

    fun cancel(fileId: String) {
        viewModelScope.launch { offline.cancel(fileId) }
    }

    fun retry(fileId: String) {
        viewModelScope.launch { offline.retry(fileId) }
    }

    fun delete(fileId: String) {
        viewModelScope.launch { offline.deleteOffline(fileId) }
    }
}
