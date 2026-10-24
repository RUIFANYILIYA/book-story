/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.presentation.book_info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.domain.use_case.book.CanResetCoverImageUseCase
import ua.acclorite.book_story.domain.repository.AccountRepository
import ua.acclorite.book_story.domain.repository.ShelfRepository
import ua.acclorite.book_story.domain.use_case.book.DeleteBookUseCase
import ua.acclorite.book_story.domain.use_case.book.GetBookUseCase
import ua.acclorite.book_story.domain.use_case.book.GetFileFromBookUseCase
import ua.acclorite.book_story.domain.use_case.book.ResetCoverImageUseCase
import ua.acclorite.book_story.domain.use_case.book.UpdateBookUseCase
import ua.acclorite.book_story.domain.use_case.book.UpdateCoverImageUseCase
import ua.acclorite.book_story.presentation.browse.BrowseScreen
import ua.acclorite.book_story.presentation.history.HistoryScreen
import ua.acclorite.book_story.presentation.library.LibraryScreen
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@HiltViewModel
class BookInfoModel @Inject constructor(
    private val updateCoverImageUseCase: UpdateCoverImageUseCase,
    private val updateBookUseCase: UpdateBookUseCase,
    private val getBookUseCase: GetBookUseCase,
    private val getFileFromBookUseCase: GetFileFromBookUseCase,
    private val deleteBookUseCase: DeleteBookUseCase,
    private val canResetCoverImageUseCase: CanResetCoverImageUseCase,
    private val resetCoverImageUseCase: ResetCoverImageUseCase,
    private val shelfRepository: ShelfRepository,
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(BookInfoState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BookInfoEffect>()
    val effects = _effects.asSharedFlow()

    private val eventStack = mutableListOf<Job>()

    fun onEvent(event: BookInfoEvent) {
        viewModelScope.launch {
            when (event) {
                is BookInfoEvent.OnShowDetailsBottomSheet -> {
                    _state.update {
                        it.copy(
                            bottomSheet = BookInfoScreen.DETAILS_BOTTOM_SHEET
                        )
                    }
                }

                is BookInfoEvent.OnShowChangeCoverBottomSheet -> {
                    _state.update {
                        it.copy(
                            bottomSheet = BookInfoScreen.CHANGE_COVER_BOTTOM_SHEET
                        )
                    }
                }

                is BookInfoEvent.OnChangeCover -> {
                    withContext(Dispatchers.Default) {
                        updateCoverImageUseCase(_state.value.book.id, event.image)
                        val updatedCoverImage = getBookUseCase(_state.value.book.id)?.coverImage
                            ?: return@withContext

                        _state.update {
                            it.copy(
                                book = it.book.copy(
                                    coverImage = updatedCoverImage
                                ),
                                bottomSheet = null,
                                canResetCover = canResetCoverImageUseCase(it.book.id)
                            )
                        }

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        _effects.emit(BookInfoEffect.OnChangedCover)
                    }
                }

                is BookInfoEvent.OnResetCover -> {
                    withContext(Dispatchers.Default) {
                        val result = resetCoverImageUseCase(_state.value.book.id)
                        if (!result) {
                            _effects.emit(BookInfoEffect.OnErrorResetCover)
                            return@withContext
                        }

                        val book = getBookUseCase(_state.value.book.id)
                        if (book == null) {
                            _effects.emit(BookInfoEffect.OnErrorResetCover)
                            return@withContext
                        }

                        _state.update {
                            it.copy(
                                book = book,
                                bottomSheet = null,
                                canResetCover = false
                            )
                        }

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        _effects.emit(BookInfoEffect.OnResetCover)
                    }
                }

                is BookInfoEvent.OnDeleteCover -> {
                    withContext(Dispatchers.Default) {
                        updateCoverImageUseCase(_state.value.book.id, null)
                        _state.update {
                            it.copy(
                                book = it.book.copy(
                                    coverImage = null
                                ),
                                bottomSheet = null,
                                canResetCover = canResetCoverImageUseCase(it.book.id)
                            )
                        }

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        _effects.emit(BookInfoEffect.OnDeletedCover)
                    }
                }

                is BookInfoEvent.OnCheckCoverReset -> {
                    withContext(Dispatchers.Default) {
                        if (_state.value.book.id == -1) return@withContext
                        val canResetCover = canResetCoverImageUseCase(_state.value.book.id)
                        _state.update {
                            it.copy(
                                canResetCover = canResetCover
                            )
                        }
                    }
                }

                is BookInfoEvent.OnDismissBottomSheet -> {
                    _state.update {
                        it.copy(
                            bottomSheet = null
                        )
                    }
                }

                is BookInfoEvent.OnShowTitleDialog -> {
                    _state.update {
                        it.copy(
                            dialog = BookInfoScreen.TITLE_DIALOG
                        )
                    }
                }

                is BookInfoEvent.OnActionTitleDialog -> {
                    withContext(Dispatchers.Default) {
                        _state.update {
                            it.copy(
                                book = it.book.copy(
                                    title = event.title
                                )
                            )
                        }
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        _effects.emit(BookInfoEffect.OnTitleChanged)
                    }
                }

                is BookInfoEvent.OnShowAuthorDialog -> {
                    _state.update {
                        it.copy(
                            dialog = BookInfoScreen.AUTHOR_DIALOG
                        )
                    }
                }

                is BookInfoEvent.OnActionAuthorDialog -> {
                    withContext(Dispatchers.Default) {
                        _state.update {
                            it.copy(
                                book = it.book.copy(
                                    author = event.author
                                )
                            )
                        }
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        _effects.emit(BookInfoEffect.OnAuthorChanged)
                    }
                }

                is BookInfoEvent.OnShowDescriptionDialog -> {
                    _state.update {
                        it.copy(
                            dialog = BookInfoScreen.DESCRIPTION_DIALOG
                        )
                    }
                }

                is BookInfoEvent.OnActionDescriptionDialog -> {
                    withContext(Dispatchers.Default) {
                        _state.update {
                            it.copy(
                                book = it.book.copy(
                                    description = event.description
                                )
                            )
                        }
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        _effects.emit(BookInfoEffect.OnDescriptionChanged)
                    }
                }

                is BookInfoEvent.OnShowPathDialog -> {
                    _state.update {
                        it.copy(
                            dialog = BookInfoScreen.PATH_DIALOG
                        )
                    }
                }

                is BookInfoEvent.OnActionPathDialog -> {
                    withContext(Dispatchers.Default) {
                        _state.update {
                            it.copy(
                                book = it.book.copy(
                                    filePath = event.path
                                )
                            )
                        }
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        _effects.emit(BookInfoEffect.OnPathChanged)

                        val file = getFileFromBookUseCase(_state.value.book.id)
                        _state.update {
                            it.copy(
                                file = file
                            )
                        }
                    }
                }

                is BookInfoEvent.OnShowDeleteDialog -> {
                    _state.update {
                        it.copy(
                            dialog = BookInfoScreen.DELETE_DIALOG
                        )
                    }
                }

                is BookInfoEvent.OnActionDeleteDialog -> {
                    withContext(Dispatchers.Default) {
                        _state.update {
                            it.copy(
                                dialog = null,
                                bottomSheet = null
                            )
                        }
                        deleteBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)
                        BrowseScreen.refreshListChannel.trySend(Unit)

                        _effects.emit(BookInfoEffect.OnBookDeleted)
                        _effects.emit(BookInfoEffect.OnNavigateBack)
                    }
                }

                is BookInfoEvent.OnShowCloudDeleteDialog -> {
                    _state.update {
                        it.copy(
                            dialog = BookInfoScreen.CLOUD_DELETE_DIALOG,
                            cloudDeleteError = null
                        )
                    }
                }

                is BookInfoEvent.OnActionCloudDeleteDialog -> {
                    withContext(Dispatchers.Default) {
                        val serverBookId = _state.value.book.serverBookId

                        if (serverBookId != null) {
                            shelfRepository.deleteOnServer(serverBookId).onFailure { error ->
                                // Removing the local copy after a refused remote delete would leave
                                // the reader with neither, so the book stays put and the reason shows.
                                _state.update {
                                    it.copy(
                                        dialog = null,
                                        cloudDeleteError = error.message
                                    )
                                }
                                return@withContext
                            }
                        }

                        _state.update {
                            it.copy(
                                dialog = null,
                                bottomSheet = null
                            )
                        }
                        deleteBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)
                        BrowseScreen.refreshListChannel.trySend(Unit)

                        _effects.emit(BookInfoEffect.OnBookDeleted)
                        _effects.emit(BookInfoEffect.OnNavigateBack)
                    }
                }

                is BookInfoEvent.OnShowMoveDialog -> {
                    _state.update {
                        it.copy(
                            dialog = BookInfoScreen.MOVE_DIALOG
                        )
                    }
                }

                is BookInfoEvent.OnActionMoveDialog -> {
                    withContext(Dispatchers.Default) {
                        // Same rule as the library's move dialog: at most one sub-shelf per book.
                        val subShelf = event.selectedCategories.lastOrNull { it.parentId != null }
                        val categoryIds = event.selectedCategories
                            .filter { it.parentId == null || it == subShelf }
                            .map { it.id }

                        _state.update {
                            it.copy(
                                book = it.book.copy(
                                    categories = categoryIds
                                ),
                                dialog = null,
                                bottomSheet = null
                            )
                        }
                        updateBookUseCase(_state.value.book)

                        LibraryScreen.refreshListChannel.trySend(0)
                        HistoryScreen.refreshListChannel.trySend(0)

                        _effects.emit(BookInfoEffect.OnBookMoved)
                    }
                }

                is BookInfoEvent.OnDismissDialog -> {
                    _state.update {
                        it.copy(
                            dialog = null
                        )
                    }
                }

                is BookInfoEvent.OnNavigateBack -> {
                    _effects.emit(BookInfoEffect.OnNavigateBack)
                }

                is BookInfoEvent.OnNavigateToLibrarySettings -> {
                    _effects.emit(BookInfoEffect.OnNavigateToLibrarySettings)
                }

                is BookInfoEvent.OnNavigateToReader -> {
                    _effects.emit(BookInfoEffect.OnNavigateToReader)
                }
            }
        }.also { eventStack.add(it) }
    }

    fun init(
        bookId: Int,
        changePath: Boolean
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            val book = getBookUseCase(bookId)

            if (book == null) {
                _effects.emit(BookInfoEffect.OnNavigateBack)
                return@launch
            }

            clear()

            _state.update {
                BookInfoState(
                    book = book,
                    cloudDeletable = book.serverBookId != null && shelfRepository.canDeleteOnServer()
                )
            }

            // Capabilities are learned on the server screen, and guessing "not allowed" just
            // because nobody asked yet would hide the option from accounts that have it.
            if (book.serverBookId != null && !_state.value.cloudDeletable) {
                accountRepository.currentAccount().onSuccess {
                    _state.update { it.copy(cloudDeletable = shelfRepository.canDeleteOnServer()) }
                }
            }

            if (changePath) onEvent(BookInfoEvent.OnShowPathDialog)
            onEvent(BookInfoEvent.OnCheckCoverReset)

            val file = getFileFromBookUseCase(bookId)
            _state.update {
                it.copy(
                    file = file
                )
            }
        }
    }

    fun clearAsync() {
        viewModelScope.launch {
            eventStack.forEach { job ->
                job.cancel()
            }
            _state.update { BookInfoState() }
        }
    }

    suspend fun clear() {
        eventStack.forEach { job ->
            job.cancel()
            job.join()
        }
        eventStack.clear()
        _state.update { BookInfoState() }
    }

    private suspend inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
        mutex.withLock {
            coroutineContext.ensureActive()
            this.value = function(this.value)
        }
    }
}