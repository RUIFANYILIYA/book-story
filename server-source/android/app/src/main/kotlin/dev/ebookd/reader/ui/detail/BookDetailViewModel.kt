package dev.ebookd.reader.ui.detail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ebookd.core.data.CoverRepository
import dev.ebookd.core.data.CoverSync
import dev.ebookd.core.data.LibraryRepository
import dev.ebookd.core.data.OfflineRepository
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.db.BookEntity
import dev.ebookd.core.net.BookDetailDto
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Book detail: fetched live, with the cached row shown underneath so the screen is never blank.
 *
 * The fallback is not decoration. The cached row already carries the title, cover, format list and
 * progress — everything the reader needs to recognise the book and to ask for it offline — so the
 * honest states are "the details are still loading" and "the details could not be loaded", not a
 * blank page that pretends the library knows nothing.
 */
@HiltViewModel
class BookDetailViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val covers: CoverRepository,
    settings: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val bookId: String = checkNotNull(savedStateHandle["bookId"]) { "book route requires a bookId" }

    data class State(
        val loading: Boolean = true,
        val detail: BookDetailDto? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val cached: StateFlow<BookEntity?> =
        library.observeCached(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * This device's percentage for the book, which outranks the cached row's — see
     * [LibraryRepository.localPercent].
     */
    val percent: StateFlow<Double?> =
        library.localPercent(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The reader's own cover for this book, if this device holds one. */
    val localCover: File? get() = covers.fileFor(bookId)

    /** Whether to offer 恢复默认: the file is the answer, and it is the same answer the shelf draws. */
    val hasLocalCover: StateFlow<Boolean> =
        covers.overrides.map { bookId in it }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * One line per action, shown as a snack bar.
     *
     * Not a field in [State]: this screen scrolls, and the result of a tap that lands under the
     * fold is a tap the reader believes did nothing. A channel also keeps two actions in a row two
     * messages rather than the second overwriting the first before it was ever drawn.
     */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /**
     * Draws the picked picture here first, then tells the server.
     *
     * A failed upload is reported as "on this device only" rather than undone, because the cover the
     * reader just chose is real to them right now and the alternative — reverting to the old cover
     * because the train went through a tunnel — throws away the action they took for a reason they
     * cannot see.
     */
    fun setCover(source: Uri) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = covers.setCover(bookId, source)
            val message = when (outcome.sync) {
                CoverSync.SYNCED -> "封面已换好，也传到服务器了（${outcome.sizeBytes / 1024} KB）"
                CoverSync.LOCAL_ONLY -> "这台手机上已经生效。${outcome.reason ?: "还没传上去"}——回到网络里再点一次就行。"
                CoverSync.FAILED -> outcome.reason ?: "这张图片用不了"
            }
            _messages.trySend(message)
            _busy.value = false
        }
    }

    fun clearCover() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = covers.clearCover(bookId)
            _messages.trySend(
                if (outcome.sync == CoverSync.SYNCED) "已恢复书库自己的封面"
                else outcome.reason ?: "没能清除，服务器上的封面还在",
            )
            _busy.value = false
        }
    }

    val coverBase: StateFlow<String?> =
        settings.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            library.detail(bookId).fold(
                onSuccess = { detail -> _state.value = State(loading = false, detail = detail) },
                onFailure = {
                    _state.update { it.copy(loading = false, error = "无法读取书籍详情：无法连接服务器") }
                },
            )
        }
    }
}
