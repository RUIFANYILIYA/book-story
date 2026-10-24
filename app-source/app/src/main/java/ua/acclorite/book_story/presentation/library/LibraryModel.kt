/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.ui.UIText
import ua.acclorite.book_story.domain.model.library.Book
import ua.acclorite.book_story.domain.model.remote.RemoteBook
import ua.acclorite.book_story.domain.model.remote.RemoteDownload
import ua.acclorite.book_story.domain.repository.ShelfRepository
import ua.acclorite.book_story.domain.use_case.book.DeleteBookUseCase
import ua.acclorite.book_story.domain.use_case.book.SearchBooksUseCase
import ua.acclorite.book_story.domain.use_case.book.UpdateBookUseCase
import ua.acclorite.book_story.domain.use_case.category.AddCategoryUseCase
import ua.acclorite.book_story.domain.use_case.category.DeleteCategoryUseCase
import ua.acclorite.book_story.domain.use_case.category.UpdateCategoryUseCase
import ua.acclorite.book_story.presentation.browse.BrowseScreen
import ua.acclorite.book_story.presentation.history.HistoryScreen
import ua.acclorite.book_story.presentation.library.model.SelectableBook
import ua.acclorite.book_story.presentation.settings.SettingsScreen
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@HiltViewModel
class LibraryModel @Inject constructor(
    private val updateBookUseCase: UpdateBookUseCase,
    private val searchBooksUseCase: SearchBooksUseCase,
    private val deleteBookUseCase: DeleteBookUseCase,
    private val addCategoryUseCase: AddCategoryUseCase,
    private val updateCategoryUseCase: UpdateCategoryUseCase,
    private val deleteCategoryUseCase: DeleteCategoryUseCase,
    private val shelfRepository: ShelfRepository
) : ViewModel() {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LibraryEffect>()
    val effects = _effects.asSharedFlow()

    init {
        onEvent(
            LibraryEvent.OnRefreshList(
                loading = true,
                hideSearch = true
            )
        )

        /* Observe channel - - - - - - - - - - - */
        viewModelScope.launch {
            LibraryScreen.refreshListChannel.receiveAsFlow().collectLatest {
                delay(it)
                onEvent(
                    LibraryEvent.OnRefreshList(
                        loading = false,
                        hideSearch = false
                    )
                )
            }
        }
        /* - - - - - - - - - - - - - - - - - - - */

        /* Server catalogue - - - - - - - - - - - */
        viewModelScope.launch {
            combine(
                shelfRepository.remoteBooks,
                shelfRepository.downloads
            ) { remote, downloads -> remote to downloads }
                .collectLatest { (remote, downloads) ->
                    _state.update {
                        it.copy(remoteBooks = buildRemoteEntries(remote, downloads))
                    }
                }
        }
        // A book that just arrived has to be read back from the database to become a real row.
        viewModelScope.launch {
            shelfRepository.arrivals.collectLatest {
                onEvent(
                    LibraryEvent.OnRefreshList(
                        loading = false,
                        hideSearch = false
                    )
                )
            }
        }
        syncShelf()
        /* - - - - - - - - - - - - - - - - - - - */
    }

    /**
     * Catalogue entries are shelf tiles the device does not hold yet, so they get an id of their own:
     * local rows come from Room and are always positive.
     */
    private fun buildRemoteEntries(
        remote: List<RemoteBook>,
        downloads: Map<String, RemoteDownload>
    ): List<SelectableBook> {
        val query =
            if (_state.value.showSearch) _state.value.searchQuery.trim()
            else ""

        return remote.filterNot { it.inLibrary }
            .filter { book ->
                query.isEmpty() ||
                        book.title.contains(query, ignoreCase = true) ||
                        book.author.contains(query, ignoreCase = true)
            }
            .map { book -> book.asRemoteEntry(downloads[book.id]) }
    }

    private fun syncShelf() {
        if (!shelfRepository.connected()) return
        viewModelScope.launch { shelfRepository.sync() }
    }

    private var refreshJob: Job? = null
    private var searchQueryChange: Job? = null

    fun onEvent(event: LibraryEvent) {
        viewModelScope.launch {
            when (event) {
                is LibraryEvent.OnRefreshList -> {
                    refreshJob?.cancel()
                    refreshJob = viewModelScope.launch(Dispatchers.Default) {
                        _state.update {
                            it.copy(
                                isRefreshing = true,
                                isLoading = event.loading,
                                showSearch = if (event.hideSearch) false else it.showSearch
                            )
                        }

                        ensureActive()
                        val books = searchBooksUseCase(
                            if (_state.value.showSearch) _state.value.searchQuery
                            else ""
                        ).map { book -> SelectableBook(book, false) }
                        syncShelf()
                        _state.update {
                            it.copy(
                                books = books,
                                remoteBooks = buildRemoteEntries(
                                    shelfRepository.remoteBooks.value,
                                    shelfRepository.downloads.value
                                ),
                                hasSelectedItems = false,
                                isLoading = false
                            )
                        }

                        delay(500) // Delay for UI smoothness
                        _state.update {
                            it.copy(
                                isRefreshing = false
                            )
                        }
                    }
                }

                is LibraryEvent.OnSearchVisibility -> {
                    if (!event.show) {
                        onEvent(
                            LibraryEvent.OnRefreshList(
                                loading = false,
                                hideSearch = true
                            )
                        )
                    }

                    _state.update {
                        it.copy(
                            showSearch = event.show,
                            searchQuery = if (event.show) "" else it.searchQuery,
                            hasFocused = if (event.show) false else it.hasFocused
                        )
                    }
                }

                is LibraryEvent.OnSearchQueryChange -> {
                    _state.update {
                        it.copy(
                            searchQuery = event.query
                        )
                    }
                    searchQueryChange?.cancel()
                    searchQueryChange = viewModelScope.launch(Dispatchers.IO) {
                        delay(500)
                        onEvent(LibraryEvent.OnSearch)
                    }
                }

                is LibraryEvent.OnSearch -> {
                    onEvent(
                        LibraryEvent.OnRefreshList(
                            loading = false,
                            hideSearch = false
                        )
                    )
                }

                is LibraryEvent.OnRequestFocus -> {
                    if (!_state.value.hasFocused) {
                        _effects.emit(LibraryEffect.OnRequestFocus)
                        _state.update {
                            it.copy(
                                hasFocused = true
                            )
                        }
                    }
                }

                is LibraryEvent.OnClearSelectedBooks -> {
                    withContext(Dispatchers.Default) {
                        _state.update {
                            it.copy(
                                books = it.books.map { book -> book.copy(selected = false) },
                                hasSelectedItems = false
                            )
                        }
                    }
                }

                is LibraryEvent.OnSelectBook -> {
                    // A long press asks *what to do with this book*, not "select it": the menu that
                    // answers that is where selection mode is entered from, so a reader has one
                    // gesture to remember instead of two.
                    if (event.select == true) {
                        _state.value.books
                            .firstOrNull { it.data.id == event.id }
                            ?.let { book ->
                                _state.update { it.copy(bookActions = book) }
                                return@launch
                            }
                    }

                    withContext(Dispatchers.Default) {
                        val editedList = _state.value.books.map {
                            if (it.data.id == event.id) it.copy(
                                selected = event.select ?: !it.selected
                            )
                            else it
                        }

                        _state.update {
                            it.copy(
                                books = editedList,
                                selectedItemsCount = editedList.filter { book -> book.selected }.size,
                                hasSelectedItems = editedList.any { book -> book.selected }
                            )
                        }
                    }
                }

                is LibraryEvent.OnSelectMultiple -> {
                    _state.update { state ->
                        state.copy(
                            bookActions = null,
                            books = state.books.map { it.copy(selected = it.data.id == event.id) },
                            remoteBooks = state.remoteBooks.map { it.copy(selected = false) },
                            selectedItemsCount = 1,
                            hasSelectedItems = true
                        )
                    }
                }

                is LibraryEvent.OnSelectBooks -> {
                    withContext(Dispatchers.Default) {
                        val editedList = _state.value.books.map { book ->
                            if (event.books.any { book.data.id == it.data.id }) book.copy(
                                selected = if (event.books.size > 1) true else !book.selected
                            )
                            else book
                        }

                        _state.update {
                            it.copy(
                                books = editedList,
                                selectedItemsCount = editedList.filter { book -> book.selected }.size,
                                hasSelectedItems = editedList.any { book -> book.selected }
                            )
                        }
                    }
                }

                is LibraryEvent.OnShowMoveDialog -> {
                    _state.update {
                        it.copy(
                            dialog = LibraryScreen.MOVE_DIALOG
                        )
                    }
                }

                is LibraryEvent.OnShowCreateShelfDialog -> {
                    _state.update {
                        it.copy(
                            dialog = LibraryScreen.CREATE_SHELF_DIALOG
                        )
                    }
                }

                is LibraryEvent.OnCreateShelf -> {
                    viewModelScope.launch {
                        addCategoryUseCase(event.title)
                        // The tabs read the shelf list out of the settings screen's state, which is a
                        // snapshot: without this the new shelf exists but only shows up after a restart.
                        SettingsScreen.refreshCategoriesChannel.trySend(Unit)
                    }
                }

                is LibraryEvent.OnSelectSubShelf -> {
                    _state.update {
                        it.copy(
                            selectedSubShelfId = event.id,
                            // Opening a folder changes what the page is showing, so it also ends
                            // arrange mode: dragging inside a folder has nothing to combine into.
                            isArranging = if (event.id != null) false else it.isArranging
                        )
                    }
                }

                is LibraryEvent.OnShowCreateSubShelfDialog -> {
                    _state.update {
                        it.copy(
                            dialog = LibraryScreen.CREATE_SUB_SHELF_DIALOG,
                            subShelfParentId = event.parentId
                        )
                    }
                }

                is LibraryEvent.OnCreateSubShelf -> {
                    val parentId = _state.value.subShelfParentId
                    if (parentId == null) {
                        _state.update { it.copy(dialog = null) }
                    } else {
                        viewModelScope.launch {
                            addCategoryUseCase(event.title, parentId)
                            SettingsScreen.refreshCategoriesChannel.trySend(Unit)
                        }
                    }
                }

                is LibraryEvent.OnShowEditSubShelfDialog -> {
                    _state.update {
                        it.copy(
                            dialog = LibraryScreen.EDIT_SUB_SHELF_DIALOG,
                            editingSubShelf = event.subCategory
                        )
                    }
                }

                is LibraryEvent.OnRenameSubShelf -> {
                    val subShelf = _state.value.editingSubShelf
                    if (subShelf != null) {
                        viewModelScope.launch {
                            updateCategoryUseCase(subShelf.copy(title = event.title))
                            SettingsScreen.refreshCategoriesChannel.trySend(Unit)
                        }
                    }
                }

                is LibraryEvent.OnDeleteSubShelf -> {
                    val subShelf = _state.value.editingSubShelf
                    if (subShelf != null) {
                        viewModelScope.launch {
                            deleteCategoryUseCase(subShelf)
                            SettingsScreen.refreshCategoriesChannel.trySend(Unit)
                        }
                        // The page filter would otherwise point at a shelf that no longer exists.
                        _state.update { it.copy(selectedSubShelfId = null) }
                    }
                }

                is LibraryEvent.OnToggleArrange -> {
                    val arranging = !_state.value.isArranging
                    _state.update {
                        if (arranging) {
                            // Entering arrange mode drops selection: a long press is the drag
                            // handle there, so there is no gesture left that means "select".
                            it.copy(
                                isArranging = true,
                                bookActions = null,
                                hasSelectedItems = false,
                                selectedItemsCount = 0,
                                books = it.books.map { book -> book.copy(selected = false) },
                                remoteBooks = it.remoteBooks.map { book -> book.copy(selected = false) }
                            )
                        } else {
                            it.copy(
                                isArranging = false,
                                combine = null,
                                dialog = if (it.dialog == LibraryScreen.COMBINE_DIALOG) null else it.dialog
                            )
                        }
                    }
                }

                is LibraryEvent.OnCombineBooks -> {
                    val parentId = event.parentId
                    // A sub-shelf has to hang from a real shelf; the default tab is the bucket for
                    // everything un-filed and owns nothing.
                    if (parentId == null || parentId == -1) return@launch

                    val dragged = _state.value.books.firstOrNull { it.data.id == event.draggedId }
                    val target = _state.value.books.firstOrNull { it.data.id == event.targetId }
                    // A catalogue tile is not a file yet, so it cannot be filed into anything.
                    if (dragged == null || target == null || dragged.data.id == target.data.id) return@launch

                    _state.update {
                        it.copy(
                            combine = CombineRequest(
                                dragged = dragged,
                                target = target,
                                parentId = parentId,
                                subShelfIds = event.subShelfIds
                            ),
                            dialog = LibraryScreen.COMBINE_DIALOG
                        )
                    }
                }

                is LibraryEvent.OnConfirmCombine -> {
                    val request = _state.value.combine ?: return@launch
                    _state.update { it.copy(dialog = null, combine = null) }

                    val subShelfId = addCategoryUseCase(event.title, request.parentId)
                    if (subShelfId == null) return@launch

                    fileIntoSubShelf(
                        books = listOf(request.dragged, request.target),
                        parentId = request.parentId,
                        subShelfId = subShelfId,
                        subShelfIds = request.subShelfIds
                    )

                    // The tab row and the folder tiles both read categories from the settings
                    // snapshot, so a new sub-shelf needs that channel nudged or it will not appear.
                    SettingsScreen.refreshCategoriesChannel.trySend(Unit)
                    onEvent(LibraryEvent.OnRefreshList(loading = false, hideSearch = false))
                }

                is LibraryEvent.OnFileIntoSubShelf -> {
                    val parentId = event.parentId ?: return@launch
                    val book = _state.value.books.firstOrNull { it.data.id == event.bookId } ?: return@launch

                    fileIntoSubShelf(
                        books = listOf(book),
                        parentId = parentId,
                        subShelfId = event.subShelfId,
                        subShelfIds = event.subShelfIds
                    )

                    onEvent(LibraryEvent.OnRefreshList(loading = false, hideSearch = false))
                }

                is LibraryEvent.OnShowBookActions -> {
                    _state.update { it.copy(bookActions = event.book) }
                }

                is LibraryEvent.OnDismissBookActions -> {
                    _state.update { it.copy(bookActions = null) }
                }

                is LibraryEvent.OnTogglePinned -> {
                    _state.update { it.copy(bookActions = null) }
                    viewModelScope.launch {
                        updateBookUseCase(event.book.data.copy(pinned = !event.book.data.pinned))
                        onEvent(LibraryEvent.OnRefreshList(loading = false, hideSearch = false))
                    }
                }

                is LibraryEvent.OnShowEditTitleDialog -> {
                    _state.update { it.copy(bookActions = null, editingTitle = event.book) }
                }

                is LibraryEvent.OnShowEditAuthorDialog -> {
                    _state.update { it.copy(bookActions = null, editingAuthor = event.book) }
                }

                is LibraryEvent.OnDismissEditDialog -> {
                    _state.update { it.copy(editingTitle = null, editingAuthor = null) }
                }

                is LibraryEvent.OnActionEditTitle -> {
                    val book = _state.value.editingTitle ?: return@launch
                    _state.update { it.copy(editingTitle = null) }
                    updateBookUseCase(book.data.copy(title = event.title))
                    onEvent(LibraryEvent.OnRefreshList(loading = false, hideSearch = false))
                }

                is LibraryEvent.OnActionEditAuthor -> {
                    val book = _state.value.editingAuthor ?: return@launch
                    _state.update { it.copy(editingAuthor = null) }
                    updateBookUseCase(book.data.copy(author = event.author))
                    onEvent(LibraryEvent.OnRefreshList(loading = false, hideSearch = false))
                }

                is LibraryEvent.OnDeleteLocalBook -> {
                    // Deleting is the one action here that cannot be undone from the reader, so it
                    // asks first — and it says "this device" in the question, because a book that
                    // came from a server is still there afterwards.
                    _state.update { it.copy(bookActions = null, pendingDelete = event.book) }
                }

                is LibraryEvent.OnActionDeleteLocalBook -> {
                    val book = _state.value.pendingDelete ?: return@launch
                    _state.update { it.copy(pendingDelete = null) }
                    deleteBookUseCase(book.data)
                    onEvent(LibraryEvent.OnRefreshList(loading = false, hideSearch = false))
                }

                is LibraryEvent.OnMoveBook -> {
                    _state.update { state ->
                        state.copy(
                            bookActions = null,
                            books = state.books.map { it.copy(selected = it.data.id == event.book.data.id) },
                            remoteBooks = state.remoteBooks.map { it.copy(selected = false) },
                            selectedItemsCount = 1,
                            hasSelectedItems = true,
                            dialog = LibraryScreen.MOVE_DIALOG
                        )
                    }
                }

                is LibraryEvent.OnDismissDeleteLocal -> {
                    _state.update { it.copy(pendingDelete = null) }
                }

                is LibraryEvent.OnActionMoveDialog -> {
                    withContext(Dispatchers.Default) {
                        // A book lives in at most one sub-shelf. The move dialog already enforces
                        // that; normalising here as well keeps every other entry point honest.
                        val subShelf = event.selectedCategories.lastOrNull { it.parentId != null }
                        val categoryIds = event.selectedCategories
                            .filter { it.parentId == null || it == subShelf }
                            .map { it.id }

                        _state.value.books.forEach { book ->
                            if (!book.selected) return@forEach
                            updateBookUseCase(
                                book.data.copy(
                                    categories = categoryIds
                                )
                            )
                        }

                        _state.update {
                            it.copy(
                                books = it.books.map { book ->
                                    if (!book.selected) return@map book
                                    book.copy(
                                        data = book.data.copy(
                                            categories = categoryIds
                                        ),
                                        selected = false
                                    )
                                },
                                hasSelectedItems = false,
                                dialog = null
                            )
                        }

                        HistoryScreen.refreshListChannel.trySend(0)
                        _effects.emit(LibraryEffect.OnBooksMoved)
                    }
                }

                is LibraryEvent.OnShowDeleteDialog -> {
                    _state.update {
                        it.copy(
                            dialog = LibraryScreen.DELETE_DIALOG
                        )
                    }
                }

                is LibraryEvent.OnActionDeleteDialog -> {
                    withContext(Dispatchers.Default) {
                        _state.value.books.forEach {
                            if (!it.selected) return@forEach
                            deleteBookUseCase(it.data)
                        }

                        _state.update {
                            it.copy(
                                books = it.books.filter { book -> !book.selected },
                                hasSelectedItems = false,
                                dialog = null
                            )
                        }

                        HistoryScreen.refreshListChannel.trySend(0)
                        BrowseScreen.refreshListChannel.trySend(Unit)
                        // A book taken off the device is again something the server can offer.
                        shelfRepository.refreshFlags()
                        _effects.emit(LibraryEffect.OnBooksDeleted)
                    }
                }

                is LibraryEvent.OnDismissDialog -> {
                    _state.update {
                        it.copy(
                            dialog = null
                        )
                    }
                }

                is LibraryEvent.OnShowFilterBottomSheet -> {
                    _state.update {
                        it.copy(
                            bottomSheet = LibraryScreen.FILTER_BOTTOM_SHEET
                        )
                    }
                }

                is LibraryEvent.OnDismissBottomSheet -> {
                    _state.update {
                        it.copy(
                            bottomSheet = null
                        )
                    }
                }

                is LibraryEvent.OnNavigateToLibrarySettings -> {
                    _effects.emit(LibraryEffect.OnNavigateToLibrarySettings)
                }

                is LibraryEvent.OnNavigateToBrowse -> {
                    _effects.emit(LibraryEffect.OnNavigateToBrowse)
                }

                is LibraryEvent.OnNavigateToBookInfo -> {
                    _effects.emit(LibraryEffect.OnNavigateToBookInfo(event.id))
                }

                is LibraryEvent.OnDownloadAll -> {
                    shelfRepository.downloadAll()
                }

                is LibraryEvent.OnNavigateToReader -> {
                    val remote = _state.value.remoteBooks
                        .firstOrNull { it.data.id == event.id }
                        ?.remote

                    // The tile stands for a file that is not here yet, so the first tap fetches it and
                    // the same tile opens the reader once it has landed.
                    if (remote != null) shelfRepository.download(remote.id)
                    else _effects.emit(LibraryEffect.OnNavigateToReader(event.id))
                }
            }
        }
    }

    /**
     * Files [books] into [subShelfId], keeping the top-level shelves they were already on and
     * dropping whichever sub-shelf they used to sit in — a book lives in at most one.
     */
    private suspend fun fileIntoSubShelf(
        books: List<SelectableBook>,
        parentId: Int,
        subShelfId: Int,
        subShelfIds: List<Int>
    ) {
        books.forEach { book ->
            val kept = book.data.categories.filter { it !in subShelfIds }
            val categories = (kept + parentId + subShelfId).distinct()
            if (categories.toSet() == book.data.categories.toSet()) return@forEach

            updateBookUseCase(book.data.copy(categories = categories))
        }
    }

    private suspend inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
        mutex.withLock {
            coroutineContext.ensureActive()
            this.value = function(this.value)
        }
    }
}

private fun RemoteBook.asRemoteEntry(download: RemoteDownload?) = SelectableBook(
    data = Book.default.copy(
        id = remoteTileId(),
        title = title,
        author = UIText.StringValue(author),
        coverImage = coverImage,
        serverBookId = id
    ),
    selected = false,
    remote = this,
    remoteDownload = download
)

/** Negative, so a catalogue tile can never be mistaken for a Room row id. */
private fun RemoteBook.remoteTileId(): Int = -(id.hashCode() and Int.MAX_VALUE) - 1