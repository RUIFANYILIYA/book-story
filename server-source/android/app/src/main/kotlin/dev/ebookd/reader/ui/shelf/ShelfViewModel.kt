package dev.ebookd.reader.ui.shelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.ebookd.core.data.BatchFailure
import dev.ebookd.core.data.BatchReport
import dev.ebookd.core.data.BatchRepository
import dev.ebookd.core.data.CapabilityStore
import dev.ebookd.core.data.CoverRepository
import dev.ebookd.core.data.LibraryRepository
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.data.SyncRepository
import dev.ebookd.core.db.BookEntity
import dev.ebookd.core.net.TagDto
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.io.File
import kotlinx.coroutines.launch

/**
 * The shelf is the one screen that must work on a train, so it renders from Room and lets the
 * mediator refresh behind it: cached rows appear immediately, and a failed refresh shows a retry
 * rather than an empty shelf.
 *
 * It is also where batch filing lives, because filing is a shelf activity: the reader is looking at
 * thirty covers and deciding what they are.
 */
@HiltViewModel
class ShelfViewModel @Inject constructor(
    library: LibraryRepository,
    settings: SettingsStore,
    sync: SyncRepository,
    private val batch: BatchRepository,
    private val covers: CoverRepository,
    capabilities: CapabilityStore,
) : ViewModel() {

    /** The catalogue, paged. Nothing is folded into the paging flow; see [percents]. */
    val books: Flow<PagingData<BookEntity>> = library.shelf().cachedIn(viewModelScope)

    /**
     * This device's own position for every book it has opened, keyed by book id.
     *
     * Beside the paging flow rather than combined into it, for two reasons that point the same way.
     * Room invalidates a query when the tables it reads change, so anything that makes the shelf
     * read `reading_position` re-runs during the one activity that must not be doing the shelf's
     * work: a page turn. And a `combine` would re-emit the pager's `PagingData` on every position
     * write — an object that carries the pager's single-shot page-event flow, which Paging refuses
     * to collect twice (`Attempt to collect twice from pageEventFlow`). A map the cards look into
     * costs neither: it is a two-column table, re-read by Room's own invalidation, and the card
     * that needs a number reads it.
     *
     * The number itself has two sources on purpose. `BookEntity.percent` is what the server said the
     * last time the list was fetched, and it is all that exists for a book this device has never
     * opened — which is exactly the case where the server knows more, because another device read
     * it. Once the book has been read here, the local row is the truth: it is written on every page
     * turn, offline included, and the server converges towards it rather than the other way round.
     */
    val percents: StateFlow<Map<String, Double>> =
        library.localPercents().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Covers are authenticated, so their URLs are built against the address that is configured now. */
    val coverBase: StateFlow<String?> =
        settings.baseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Books whose position or status has not reached the server yet; the top bar shows the count. */
    val pending: StateFlow<Int> =
        combine(sync.observePending(), batch.observePendingCount()) { positions, statuses -> positions + statuses }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Which covers the reader replaced on this device.
     *
     * The shelf draws those instead of the server's URL, so a cover picked on the book's page is
     * visible the moment the reader goes back — and stays visible offline, because the file is the
     * cover here rather than a hint about what the server will send.
     */
    val localCovers: StateFlow<Set<String>> = covers.overrides

    /** The file to draw for one book, or null when the server's cover is the one to draw. */
    fun coverFile(bookId: String): File? = covers.fileFor(bookId)

    /**
     * What this session may do, as the server answered.
     *
     * Tagging and deleting are writes to shared metadata, so their menu items appear only with the
     * capability behind them. The list starts empty, which means a shelf drawn before the answer
     * arrives offers marking-only — the honest reading of a session nobody has checked.
     */
    val capabilities: StateFlow<Set<String>> =
        capabilities.capabilities.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _selecting = MutableStateFlow(false)

    /** Selection mode is its own state rather than "the set is not empty", so deselecting everything leaves the bar up. */
    val selecting: StateFlow<Boolean> = _selecting.asStateFlow()

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * Statuses the reader chose here that the server has not accepted yet, and the badge to draw for
     * them.
     *
     * A Room-backed queue rather than the in-memory map this used to be. The map was the right layer
     * for a request in flight — dropping it *is* the rollback — but it forgot everything when the
     * process died, which is exactly the case the queue exists for: a status chosen on a train is
     * still on the card when the reader opens the app at the station, whether or not the server has
     * heard of it yet. It is still not a write to `books.status`, which the shelf's refresh clears
     * and re-fetches on every invalidation.
     */
    val pendingStatus: StateFlow<Map<String, String>> =
        batch.pendingStatuses().stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One line per batch, shown as a snack bar. A channel rather than a state flow: two batches in a row are two messages. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun enterSelection() {
        _selecting.value = true
    }

    fun exitSelection() {
        _selecting.value = false
        _selection.value = emptySet()
    }

    fun toggle(id: String) {
        _selection.update { if (id in it) it - id else it + id }
    }

    fun selectAll(ids: Collection<String>) {
        _selection.value = ids.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun markStatus(code: String) = runBatch(
        action = { batch.markStatus(it, code) },
        message = { statusMessage(it, code) },
    )

    fun addTags(names: List<String>) = runBatch(
        action = { batch.addTags(it, names) },
        message = { tagMessage(it, names) },
    )

    fun removeBooks() = runBatch(
        action = { batch.removeBooks(it) },
        message = { deleteMessage(it) },
    )

    /** The picker's list. Empty on failure is fine to show: a typed name still files the book, because the server creates tags on the way in. */
    suspend fun tags(query: String): List<TagDto> = batch.tags(query).getOrDefault(emptyList())

    private fun runBatch(
        action: suspend (List<String>) -> BatchReport,
        message: (BatchReport) -> String,
    ) {
        val ids = _selection.value.toList()
        if (ids.isEmpty() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            // Nothing here is allowed to throw: a batch that died for a reason nobody modelled still
            // has to say so, or the reader is left looking at a shelf that appears to have accepted
            // what it did not.
            val report = runCatching { action(ids) }.getOrElse { BatchReport(failed = ids, failure = BatchFailure.OTHER) }
            _messages.trySend(message(report))
            if (report.applied.isNotEmpty()) _selection.value = emptySet()
            _busy.value = false
        }
    }

    private fun statusMessage(
        report: BatchReport,
        code: String,
    ): String = when {
        // The badge stays and the queue keeps the intent, so the honest sentence is "remembered", not
        // "nothing happened" — which is what this said before there was a queue, and was true then.
        report.failure == BatchFailure.OFFLINE ->
            "已把 ${report.failed.size} 本记为「${ReadingStatus.badge(code) ?: "未开始"}」，回到网络会自动上传"
        report.failure == BatchFailure.FORBIDDEN -> "这台服务器不让你改共享元数据"
        report.failure != BatchFailure.NONE -> "没改成功：服务器没有接受这次操作"
        report.failed.isEmpty() -> "已把 ${report.applied.size} 本标为「${ReadingStatus.badge(code) ?: "未开始"}」"
        report.applied.isEmpty() -> "这 ${report.failed.size} 本没能改，可能已经不在了"
        else -> "改了 ${report.applied.size} 本，另有 ${report.failed.size} 本没改成"
    }

    private fun tagMessage(
        report: BatchReport,
        names: List<String>,
    ): String {
        val label = names.joinToString("、") { "「$it」" }
        return when (report.failure) {
            BatchFailure.OFFLINE -> "离线了，没有打上 $label"
            BatchFailure.FORBIDDEN -> "打标签要库的写入权限，你的账号没有"
            BatchFailure.NONE -> when {
                report.failed.isEmpty() -> "已给 ${report.applied.size} 本加上 $label"
                report.applied.isEmpty() -> "$label 没能加上，这些书可能已经不在了"
                else -> "${report.applied.size} 本加上了 $label，另有 ${report.failed.size} 本没加上"
            }
            BatchFailure.OTHER -> "没加上 $label：服务器没有接受这次操作"
        }
    }

    private fun deleteMessage(report: BatchReport) = when (report.failure) {
        BatchFailure.OFFLINE -> "离线了，没有移除任何东西"
        BatchFailure.FORBIDDEN -> "从服务器移除要管理权限，你的账号没有"
        BatchFailure.NONE -> when {
            report.failed.isEmpty() -> "已移除 ${report.applied.size} 本"
            report.applied.isEmpty() -> "没能移除这 ${report.failed.size} 本"
            else -> "移除了 ${report.applied.size} 本，另有 ${report.failed.size} 本没动"
        }
        BatchFailure.OTHER -> "没移除成功：服务器没有接受这次操作"
    }
}
