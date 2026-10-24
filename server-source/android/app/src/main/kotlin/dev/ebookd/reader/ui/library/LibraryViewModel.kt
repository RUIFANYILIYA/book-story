package dev.ebookd.reader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.ebookd.core.data.CoverRepository
import dev.ebookd.core.data.LibraryRepository
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.db.BookEntity
import dev.ebookd.core.net.BookSummaryDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.io.File
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The catalogue: the cached shelf list while the search box is empty, and server-side search
 * results when it is not.
 *
 * Search is online for now, and says so when it fails. Pretending otherwise would need local
 * full-text search over the cache, which belongs with the offline milestone — and a "no results"
 * message produced by an unreachable server would be a lie the reader cannot detect.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val covers: CoverRepository,
    settings: SettingsStore,
) : ViewModel() {

    data class State(
        val searching: Boolean = false,
        val results: List<BookSummaryDto> = emptyList(),
        val error: String? = null,
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val catalogue: Flow<PagingData<BookEntity>> = library.shelf().cachedIn(viewModelScope)

    /** The reader's own covers on this device, so a book looks the same here as on the shelf. */
    val localCovers: StateFlow<Set<String>> = covers.overrides

    fun coverFile(bookId: String): File? = covers.fileFor(bookId)

    val coverBase: StateFlow<String?> =
        settings.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            _query
                // A request per keystroke would be both slow and rude; 250 ms is where typing a
                // Chinese phrase stops producing a request per character.
                .debounce(250)
                .distinctUntilChanged()
                .collect { text -> search(text) }
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    private suspend fun search(text: String) {
        if (text.isBlank()) {
            _state.value = State()
            return
        }
        _state.update { it.copy(searching = true, error = null) }
        library.search(text.trim()).fold(
            onSuccess = { found -> _state.value = State(searching = false, results = found) },
            onFailure = { _state.value = State(searching = false, error = "搜索失败：无法连接服务器") },
        )
    }
}
