/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.repository

import android.app.Application
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ua.acclorite.book_story.core.ui.UIText
import ua.acclorite.book_story.data.di.ApplicationScope
import ua.acclorite.book_story.data.local.dto.BookEntity
import ua.acclorite.book_story.data.local.dto.CategoryEntity
import ua.acclorite.book_story.data.local.room.BookDatabase
import ua.acclorite.book_story.data.model.file.CachedFileCompat
import ua.acclorite.book_story.data.parser.FileParser
import ua.acclorite.book_story.data.remote.BookDownloader
import ua.acclorite.book_story.data.remote.BookSummaryDto
import ua.acclorite.book_story.data.remote.EbookdApi
import ua.acclorite.book_story.data.remote.TokenStore
import ua.acclorite.book_story.domain.model.remote.RemoteBook
import ua.acclorite.book_story.domain.model.remote.RemoteDownload
import ua.acclorite.book_story.domain.model.remote.RemoteDownloadPhase
import ua.acclorite.book_story.domain.repository.AccountProblem
import ua.acclorite.book_story.domain.repository.BookRepository
import ua.acclorite.book_story.domain.repository.ServerChanges
import ua.acclorite.book_story.domain.repository.ShelfRepository
import ua.acclorite.book_story.domain.repository.ShelfSyncResult
import ua.acclorite.book_story.presentation.library.model.LibrarySortOrder
import ua.acclorite.book_story.domain.service.ServerBookFiles
import ua.acclorite.book_story.domain.use_case.book.AddBookUseCase

@Singleton
class ShelfRepositoryImpl @Inject constructor(
    private val application: Application,
    private val serverBookFiles: ServerBookFiles,
    private val api: EbookdApi,
    private val downloader: BookDownloader,
    private val tokens: TokenStore,
    private val fileParser: FileParser,
    private val addBook: AddBookUseCase,
    private val bookRepository: BookRepository,
    private val database: BookDatabase,
    @ApplicationScope private val scope: CoroutineScope
) : ShelfRepository {

    private val _remoteBooks = MutableStateFlow<List<RemoteBook>>(emptyList())
    override val remoteBooks = _remoteBooks.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    override val syncing = _syncing.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, RemoteDownload>>(emptyMap())
    override val downloads = _downloads.asStateFlow()

    private val _arrivals = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val arrivals = _arrivals.asSharedFlow()

    private val slots = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    /** Parsing is CPU and database work, so it gets its own budget instead of the network's. */
    private val parseSlots = Semaphore(MAX_CONCURRENT_PARSES)
    private var coverJob: Job? = null

    override fun connected(): Boolean = tokens.baseUrl() != null

    override suspend fun sync(): Result<List<RemoteBook>> {
        if (!_syncing.compareAndSet(expect = false, update = true))
            return runCatching { _remoteBooks.value }

        return runCatching {
            val found = fetchCatalogue()
            val onDevice = onDeviceIds()
            _remoteBooks.value = found.map { it.toRemote(onDevice) }
            prefetchCovers(found)
            _remoteBooks.value
        }.also {
            _syncing.value = false
        }
    }

    /**
     * The whole catalogue, page by page. Both the shelf sync and the change check read exactly
     * this, so they cannot drift on what "the server's books" means.
     */
    private suspend fun fetchCatalogue(): List<BookSummaryDto> {
        val baseUrl = server()
        val found = ArrayList<BookSummaryDto>()
        var cursor: String? = null
        var pages = 0

        do {
            val page = api.books(baseUrl, cursor)
            found += page.items
            cursor = page.nextCursor
        } while (page.hasMore && cursor != null && ++pages < MAX_PAGES)

        return found
    }

    /**
     * Pulls the shelf tree and files this device's books into it.
     *
     * Matching is by (level, name) rather than by id, because the two sides mint their own ids and
     * there is no shared key to join on. That is what makes "only add, never delete" possible: a
     * local shelf with a name the server also uses becomes the same shelf, and a local shelf the
     * server has never heard of simply stays.
     *
     * Books are filed by their server id first. A book that was picked from storage, or taken from
     * a different server, carries no id this catalogue recognises, so the shelf it belongs to
     * would stay empty — hence a second pass that identifies such a book by its bytes: the server
     * publishes a sha256 per file, and a local file whose hash equals one of a server book's file
     * hashes *is* that server book. Only the id, the shelf and (because the bytes are identical)
     * the content stamp are written; progress, pinning and everything else the reader owns stays
     * on the row untouched.
     */
    override suspend fun syncShelves(): Result<ShelfSyncResult> = runCatching {
        val baseUrl = server()
        val remoteShelves = api.categories(baseUrl)
        if (remoteShelves.isEmpty()) return@runCatching ShelfSyncResult()

        val categoryDao = database.categoryDao
        val bookDao = database.bookDao

        // Every local shelf keyed by (parent, name), so a second sync reuses what the first made.
        val localIds = HashMap<Pair<Int?, String>, Int>()
        categoryDao.getCategories()
            .filter { it.id != -1 }
            .forEach { localIds[it.parentId to it.title.trim()] = it.id }

        var created = 0
        val mapped = HashMap<String, Int>()

        // Roots before children: a sub-shelf cannot be filed until its parent has a local id.
        remoteShelves.filter { it.parentId == null }.sortedBy { it.position }.forEach { shelf ->
            val name = shelf.name.trim()
            val key = null to name
            mapped[shelf.id] = localIds[key] ?: run {
                val id = categoryDao.insertCategory(
                    CategoryEntity(
                        title = name,
                        order = categoryDao.getRootCategoriesCount() + created,
                        sortOrder = LibrarySortOrder.LAST_READ,
                        sortOrderDescending = true,
                        parentId = null
                    )
                ).toInt()
                localIds[key] = id
                created += 1
                id
            }
        }

        val childOrders = HashMap<Int, Int>()
        remoteShelves.filter { it.parentId != null }.sortedBy { it.position }.forEach { shelf ->
            val parentLocal = mapped[shelf.parentId] ?: return@forEach
            val name = shelf.name.trim()
            val key = parentLocal to name
            mapped[shelf.id] = localIds[key] ?: run {
                val order = childOrders.getOrPut(parentLocal) {
                    categoryDao.getChildCategoriesCount(parentLocal)
                }
                val id = categoryDao.insertCategory(
                    CategoryEntity(
                        title = name,
                        order = order,
                        sortOrder = LibrarySortOrder.LAST_READ,
                        sortOrderDescending = true,
                        parentId = parentLocal
                    )
                ).toInt()
                childOrders[parentLocal] = order + 1
                localIds[key] = id
                created += 1
                id
            }
        }

        // Which shelf each server book sits in. Same pages the catalogue is read in, from the
        // same helper, so the two cannot drift on what "the server's books" means.
        val catalogue = fetchCatalogue()
        val shelfOfBook = HashMap<String, String>()
        catalogue.forEach { book -> book.categoryId?.let { shelfOfBook[book.id] = it } }

        val parentOfShelf = remoteShelves.associate { it.id to it.parentId }

        var filed = 0
        bookDao.findSyncedBooks().forEach { entity ->
            val serverBookId = entity.serverBookId ?: return@forEach
            val serverShelf = shelfOfBook[serverBookId] ?: return@forEach
            val localShelf = mapped[serverShelf] ?: return@forEach

            // A book inside a sub-shelf keeps the parent shelf too, which is the shape the library
            // screen reads: one parent, plus at most one sub-shelf.
            val parentLocal = parentOfShelf[serverShelf]?.let { mapped[it] }
            val target = listOfNotNull(parentLocal, localShelf)

            if (entity.categories.toSet() == target.toSet()) return@forEach

            bookDao.updateBook(entity.copy(categories = target))
            filed += 1
        }

        // ---- Second pass: the books no server id could place, identified by content ----
        //
        // Everything above keys on serverBookId, which only a download ever writes. A book picked
        // from storage — or taken from a different server — has none, so its shelf stays empty no
        // matter how many times the button is pressed. The bytes, though, are usually the very
        // bytes the server holds: hash the local file, ask the server which book owns a file with
        // that hash, and the identity is established without moving a single byte of book.
        var matched = 0
        val catalogueIds = catalogue.mapTo(HashSet()) { it.id }
        val unidentified = bookDao.searchBooks("").filter { entity ->
            val id = entity.serverBookId
            id == null || id !in catalogueIds
        }
        if (unidentified.isNotEmpty()) {
            val shelved = catalogue.filter { it.categoryId != null }
            val byTitle = shelved.groupBy { normalizeTitle(it.title) }
            val bySize = shelved.groupBy { it.sizeBytes }

            // Hashing and the probe requests are IO; the caller is usually the main thread.
            matched = withContext(Dispatchers.IO) {
                var count = 0
                val claimed = HashSet<String>()
                for (entity in unidentified) {
                    val local = File(entity.filePath)
                    if (!local.isFile || !local.canRead()) continue

                    val digest = local.sha256Hex() ?: continue
                    // Title first, size as the second chance: both come from the catalogue, so
                    // narrowing costs no request. The hash is what actually decides.
                    val probes = (
                        byTitle[normalizeTitle(entity.title)].orEmpty() +
                            bySize[local.length()].orEmpty()
                        )
                        .distinctBy { it.id }
                        .filterNot { it.id in claimed }
                        .take(MAX_MATCH_PROBES)
                    if (probes.isEmpty()) continue

                    val hit = probes.firstOrNull { probe ->
                        runCatching { api.book(baseUrl, probe.id) }
                            .getOrNull()
                            ?.files
                            ?.any { file ->
                                file.sha256.isNotBlank() &&
                                    file.sha256.equals(digest, ignoreCase = true)
                            } == true
                    } ?: continue

                    val serverShelf = shelfOfBook[hit.id] ?: continue
                    val localShelf = mapped[serverShelf] ?: continue
                    val parentLocal = parentOfShelf[serverShelf]?.let { mapped[it] }
                    val target = listOfNotNull(parentLocal, localShelf)

                    // One server book, one local row: a second copy of the same bytes is left
                    // alone rather than silently merged into the first.
                    claimed += hit.id
                    bookDao.updateBook(
                        entity.copy(
                            serverBookId = hit.id,
                            // The bytes are the server's current bytes, so its stamp is this
                            // copy's stamp — recorded now instead of baselined on the next check.
                            serverContentUpdatedAt = hit.contentUpdatedAt.takeIf { it.isNotBlank() },
                            categories = target
                        )
                    )
                    count += 1
                }
                count
            }
        }

        ShelfSyncResult(
            serverShelves = remoteShelves.size,
            shelvesCreated = created,
            booksFiled = filed,
            booksMatched = matched
        )
    }

    /**
     * What the server has that this device does not, or has a newer copy of — without moving a
     * single byte of book.
     *
     * The catalogue already carries everything the answer needs: a book id this device has never
     * seen is new, and a `content_updated_at` that differs from the one recorded at the last
     * import means the bytes changed since. Books synced by a version that did not record the
     * stamp are baselined rather than reported: guessing that all of them changed would train the
     * reader to dismiss the dialog, and the next check is exact anyway.
     */
    override suspend fun checkServerChanges(): Result<ServerChanges> = runCatching {
        val catalogue = fetchCatalogue()
        val bookDao = database.bookDao
        val local = bookDao.findSyncedBooks()
            .mapNotNull { entity -> entity.serverBookId?.let { id -> id to entity } }
            .toMap()

        val fresh = ArrayList<RemoteBook>()
        val stale = ArrayList<RemoteBook>()
        catalogue.forEach { dto ->
            val entity = local[dto.id]
            if (entity == null) {
                fresh += dto.toRemote(local.keys)
                return@forEach
            }
            val stamp = dto.contentUpdatedAt.takeIf { it.isNotBlank() } ?: return@forEach
            if (entity.serverContentUpdatedAt == null) {
                bookDao.updateBook(entity.copy(serverContentUpdatedAt = stamp))
            } else if (stamp != entity.serverContentUpdatedAt) {
                stale += dto.toRemote(local.keys)
            }
        }

        ServerChanges(newBooks = fresh, updatedBooks = stale)
    }

    /**
     * Takes everything a [checkServerChanges] found: the new books go through the ordinary
     * download queue, the updated ones through [updateBook], which keeps the local row.
     */
    override fun downloadChanges(changes: ServerChanges) {
        changes.newBooks.forEach { download(it.id) }
        changes.updatedBooks.forEach { updateBook(it.id) }
    }

    /**
     * What the transfer stage hands to the parse stage: the bytes are on disk, and the catalogue's
     * own answers are captured while the connection is still worth reusing.
     */
    private class Staged(
        val serverBookId: String,
        val title: String,
        val authors: String,
        val description: String?,
        val dest: File,
        val expected: Long,
        val coverBytes: ByteArray?,
        /** When the server last changed this book's content; recorded on the local row. */
        val contentUpdatedAt: String?
    )

    /** The transfer stage: everything here waits on the network. */
    private suspend fun fetch(serverBookId: String, onProgress: (Float) -> Unit): Staged {
        val baseUrl = server()
        val detail = api.book(baseUrl, serverBookId)
        val file = detail.files.firstOrNull { it.isPrimary && it.format in SUPPORTED }
            ?: detail.files.firstOrNull { it.format in SUPPORTED }
            ?: throw IOException("No readable format on the server for this book")

        val dir = serverBookFiles.dirFor(serverBookId)
        val dest = File(dir, sanitize(file.filename.ifBlank { "$serverBookId.${file.format}" }))
        val expected = dest.length().takeIf { dest.exists() } ?: 0L

        downloader.download(file.id, file.sha256, dest, knownSize = file.sizeBytes) { written, total ->
            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
        }

        // A cover file that exists but does not decode — the server draws an SVG for every coverless
        // book — is already as good as this cover will get. Asking the server again would download
        // the same bytes on every import of every coverless book.
        val local = serverBookFiles.coverFor(serverBookId).takeIf { it.exists() }?.readBytes()
        val coverBytes = local ?: api.cover(serverBookId)

        val meta = detail.book
        return Staged(
            serverBookId = serverBookId,
            title = meta.title,
            authors = meta.authors.joinToString(", "),
            description = meta.description,
            dest = dest,
            expected = expected,
            coverBytes = coverBytes,
            contentUpdatedAt = meta.contentUpdatedAt
        )
    }

    /**
     * The parse stage: charset detection, chapter splitting and the database writes. No network.
     *
     * With [existing] set this is an update rather than an import: the same local row is rewritten
     * with the newly parsed text and the server's current metadata, and everything the reader
     * owns — id, progress, shelves, pinned — is carried across untouched. That is the whole
     * difference between "the book was updated" and "the book now exists twice".
     */
    private suspend fun ingest(staged: Staged, onProgress: (Float) -> Unit, existing: BookEntity? = null) {
        val dest = staged.dest
        val cached = CachedFileCompat.fromUri(
            context = application,
            uri = dest.toUri(),
            builder = CachedFileCompat.build(
                name = dest.name,
                path = dest.absolutePath,
                size = dest.length(),
                lastModified = dest.lastModified(),
                isDirectory = false
            )
        )

        val parsed = fileParser.parse(cached)
            ?: throw IOException("The file arrived but could not be parsed")

        // The catalogue knows more than a filename does — author and blurb come from the server;
        // the parser's own guesses only fill the gaps.
        val merged = parsed.book.copy(
            id = existing?.id ?: parsed.book.id,
            serverBookId = staged.serverBookId,
            serverContentUpdatedAt = staged.contentUpdatedAt,
            title = staged.title.ifBlank { parsed.book.title },
            author = if (staged.authors.isNotBlank()) {
                UIText.StringValue(staged.authors)
            } else {
                parsed.book.author
            },
            description = staged.description ?: parsed.book.description,
            // Local state follows the existing row, never the freshly parsed defaults.
            progress = existing?.progress ?: parsed.book.progress,
            scrollIndex = existing?.scrollIndex ?: parsed.book.scrollIndex,
            scrollOffset = existing?.scrollOffset ?: parsed.book.scrollOffset,
            categories = existing?.categories ?: parsed.book.categories,
            pinned = existing?.pinned ?: false,
            coverImage = existing?.image?.toUri() ?: parsed.book.coverImage
        )

        if (existing == null) {
            val remoteCover = staged.coverBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            addBook(merged, remoteCover ?: parsed.coverImage)
        } else {
            // A file the server renamed leaves the previous bytes behind in the same directory.
            // The directory is ours, so the stale copy goes — but only a path inside it, never a
            // book that was picked from storage and merely happens to carry a server id.
            if (staged.dest.absolutePath != existing.filePath) {
                val stale = File(existing.filePath)
                val owned = stale.parentFile?.absolutePath ==
                    serverBookFiles.dirFor(staged.serverBookId).absolutePath
                if (owned) stale.delete()
            }
            bookRepository.updateBook(merged)
        }
        if (staged.expected > 0 && staged.expected == dest.length()) onProgress(1f)
    }

    override suspend fun importBook(
        serverBookId: String,
        onProgress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        if (database.bookDao.findByServerBookId(serverBookId) != null) return@runCatching Unit
        ingest(fetch(serverBookId, onProgress), onProgress)
    }

    override fun download(serverBookId: String) {
        val running = _downloads.value[serverBookId]
        if (running != null && running.phase != RemoteDownloadPhase.FAILED) return

        _downloads.update { it + (serverBookId to RemoteDownload(RemoteDownloadPhase.QUEUED, 0f)) }
        scope.launch {
            val report: (Float) -> Unit = { fraction ->
                _downloads.update {
                    it + (serverBookId to RemoteDownload(RemoteDownloadPhase.RUNNING, fraction))
                }
            }

            // The transfer holds a network slot; the parse does not. Holding the slot through the
            // parse kept the link idle for most of every book — the whole point of a queue is that
            // the next book's bytes move while this one's chapters are being cut.
            val fetched = runCatching {
                slots.withPermit {
                    if (database.bookDao.findByServerBookId(serverBookId) != null) return@launch
                    _downloads.update {
                        it + (serverBookId to RemoteDownload(RemoteDownloadPhase.RUNNING, 0f))
                    }
                    fetch(serverBookId, report)
                }
            }

            fetched.mapCatching { staged ->
                parseSlots.withPermit { ingest(staged, report) }
            }.onSuccess {
                _downloads.update { it - serverBookId }
                refreshFlags()
                _arrivals.tryEmit(serverBookId)
            }.onFailure {
                _downloads.update {
                    it + (serverBookId to RemoteDownload(RemoteDownloadPhase.FAILED, 0f))
                }
            }
        }
    }

    override fun downloadAll() {
        _remoteBooks.value.filterNot { it.inLibrary }.forEach { download(it.id) }
    }

    /**
     * Re-takes a book whose bytes changed on the server, in place.
     *
     * The same queue shape as [download] — the library screen's progress and retry affordances
     * read the same map — but the parse stage rewrites the existing row instead of inserting a
     * second one, so a reader who was on chapter 40 of the old file is on chapter 40 of the new
     * one. A book that is not on the device any more is left alone: it is a download, not an
     * update, and [download] is what the caller should have used.
     */
    override fun updateBook(serverBookId: String) {
        val running = _downloads.value[serverBookId]
        if (running != null && running.phase != RemoteDownloadPhase.FAILED) return

        _downloads.update { it + (serverBookId to RemoteDownload(RemoteDownloadPhase.QUEUED, 0f)) }
        scope.launch {
            val report: (Float) -> Unit = { fraction ->
                _downloads.update {
                    it + (serverBookId to RemoteDownload(RemoteDownloadPhase.RUNNING, fraction))
                }
            }

            val existing = database.bookDao.findByServerBookId(serverBookId)
            if (existing == null) {
                _downloads.update { it - serverBookId }
                return@launch
            }

            val fetched = runCatching {
                slots.withPermit {
                    _downloads.update {
                        it + (serverBookId to RemoteDownload(RemoteDownloadPhase.RUNNING, 0f))
                    }
                    fetch(serverBookId, report)
                }
            }

            fetched.mapCatching { staged ->
                parseSlots.withPermit { ingest(staged, report, existing) }
            }.onSuccess {
                _downloads.update { it - serverBookId }
                refreshFlags()
                _arrivals.tryEmit(serverBookId)
            }.onFailure {
                _downloads.update {
                    it + (serverBookId to RemoteDownload(RemoteDownloadPhase.FAILED, 0f))
                }
            }
        }
    }

    override suspend fun refreshFlags() {
        if (_remoteBooks.value.isEmpty()) return
        val ids = onDeviceIds()
        _remoteBooks.update { list ->
            list.map { book ->
                val onDevice = book.id in ids
                if (book.inLibrary == onDevice) book else book.copy(inLibrary = onDevice)
            }
        }
    }

    override fun canDeleteOnServer(): Boolean =
        tokens.baseUrl() != null && BATCH_DELETE in tokens.capabilities()

    override suspend fun deleteOnServer(serverBookId: String): Result<Unit> =
        runCatching {
            val outcome = api.batchDelete(listOf(serverBookId))
            val change = outcome.changed.firstOrNull { it.id == serverBookId }
            if (change == null || !change.ok) {
                throw AccountProblem(
                    change?.error ?: "not_deleted",
                    change?.error ?: "The server did not remove this book"
                )
            }
            Unit
        }.recoverCatching { error ->
            throw if (error is AccountProblem) error else AccountProblem("unreachable", error.message.orEmpty())
        }

    override fun forgetCovers() {
        serverBookFiles.clearCovers()
        _remoteBooks.value = emptyList()
    }

    /**
     * Covers are what make the shelf readable as a shelf, but a slow or missing one must never hold
     * up the list — so they arrive after the titles, a few at a time, and update in place.
     */
    private fun prefetchCovers(items: List<BookSummaryDto>) {
        coverJob?.cancel()
        val wanted = items.filter { it.hasCover || !it.coverUrl.isNullOrBlank() }
        if (wanted.isEmpty()) return

        coverJob = scope.launch(Dispatchers.IO) {
            val gate = Semaphore(MAX_CONCURRENT_COVERS)
            wanted.map { entry ->
                launch {
                    gate.withPermit {
                        if (serverBookFiles.coverFor(entry.id).exists()) return@withPermit
                        val bytes = runCatching { api.cover(entry.id) }.getOrNull() ?: return@withPermit
                        val uri = serverBookFiles.saveCover(entry.id, bytes) ?: return@withPermit
                        _remoteBooks.update { list ->
                            list.map { if (it.id == entry.id) it.copy(coverImage = uri) else it }
                        }
                    }
                }
            }.joinAll()
        }
    }

    private suspend fun onDeviceIds(): Set<String> =
        database.bookDao.findSyncedBooks().mapNotNull { it.serverBookId }.toSet()

    private fun BookSummaryDto.toRemote(onDevice: Set<String>) = RemoteBook(
        id = id,
        title = title,
        author = authors.joinToString(", "),
        formats = formats,
        sizeBytes = sizeBytes,
        percent = my.percent,
        coverImage = serverBookFiles.coverFor(id).takeIf { it.exists() }?.toUri(),
        inLibrary = id in onDevice
    )

    private fun server(): String =
        tokens.baseUrl() ?: throw IOException("No server configured")

    /**
     * Lower-cased, whitespace-collapsed title — the candidate key for the content-match pass.
     * The server's title comes from its metadata, the local one from the file, so they agree
     * often enough to be worth one map lookup; the hash is what actually decides.
     */
    private fun normalizeTitle(title: String): String =
        title.trim().lowercase().replace(WHITESPACE, " ")

    /** SHA-256 of the file as lowercase hex, or null when it cannot be read at all. */
    private fun File.sha256Hex(): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun sanitize(name: String) =
        name.replace('/', '_').replace('\\', '_').trim().ifBlank { "book" }

    private companion object {
        const val MAX_PAGES = 20
        const val MAX_CONCURRENT_DOWNLOADS = 2
        const val MAX_CONCURRENT_PARSES = 3
        const val MAX_CONCURRENT_COVERS = 4
        /** Per book, not per sync: enough to disambiguate, few enough to stay a button press. */
        const val MAX_MATCH_PROBES = 5
        val WHITESPACE = Regex("\\s+")
        const val BATCH_DELETE = "batch:delete"
        val SUPPORTED = setOf("txt", "epub", "pdf", "fb2", "html", "htm")
    }
}
