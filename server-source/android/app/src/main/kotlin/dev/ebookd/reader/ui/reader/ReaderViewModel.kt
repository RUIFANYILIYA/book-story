package dev.ebookd.reader.ui.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.ebookd.core.data.LibraryRepository
import dev.ebookd.core.data.OfflineRepository
import dev.ebookd.core.data.OpenBook
import dev.ebookd.core.data.ReaderRepository
import dev.ebookd.core.data.ReaderSettings
import dev.ebookd.core.data.SettingsStore
import dev.ebookd.core.data.UnreadableBook
import dev.ebookd.core.data.UnreadableReason
import dev.ebookd.core.db.DownloadTaskEntity
import dev.ebookd.core.text.StageTimer
import dev.ebookd.core.text.TxtBookOpener.OpenedTxtBook
import dev.ebookd.engine.android.AndroidGlyphMeasurer
import dev.ebookd.engine.android.CoverMotion
import dev.ebookd.engine.android.NoneMotion
import dev.ebookd.engine.android.PageMotion
import dev.ebookd.engine.android.ReaderPalette
import dev.ebookd.engine.android.SlideMotion
import dev.ebookd.engine.core.Chapter
import dev.ebookd.engine.core.Page
import dev.ebookd.engine.core.PageSpec
import dev.ebookd.engine.core.Paginator
import dev.ebookd.reader.VolumeKeyRelay
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One open book: the document, the current chapter's layout, and the reader's settings.
 *
 * The screen owns pixels; this owns what they mean, so layout is driven from the view's measured
 * size ([setSize]) rather than a device constant. Every re-layout re-enters through a character
 * offset, which is the whole of the M10 acceptance rule: change the font size three times and the
 * reader is still looking at the same *paragraph*, because an offset is a fact about the chapter
 * while a page number is a fact about the layout.
 */
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val reader: ReaderRepository,
    private val library: LibraryRepository,
    private val offline: OfflineRepository,
    private val settings: SettingsStore,
    private val volumeKeys: VolumeKeyRelay,
    private val openBook: OpenBook,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val bookId: String = checkNotNull(savedStateHandle["bookId"]) { "reader route requires a bookId" }

    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        /** What the spinner is waiting on when it is not the parse — a download started here. */
        val waiting: String? = null,
        val title: String = "",
        val settings: ReaderSettings = ReaderSettings(DEFAULT_FONT, 1.6f, 18f, "day", "slide"),
        val chapterIndex: Int = 0,
        val chapterCount: Int = 1,
        val chapterTitle: String = "",
        val pages: List<Page> = emptyList(),
        val text: String = "",
        val pageIndex: Int = 0,
        /**
         * Where the reader actually is, in characters of the current chapter's laid-out text.
         *
         * Not derived from [pageIndex]: a page start is a property of the layout, so re-reading it
         * after a font change would move the anchor backwards to the top of its page every time the
         * reader turned a knob.
         */
        val anchorOffset: Int = 0,
        /**
         * How far through the *book* the reader is, 0..100 — the unit the server's merge rule and
         * the stored row both use, so it is also the number the label on the bar shows.
         */
        val percent: Float = 0f,
        val chromeVisible: Boolean = true,
        /**
         * A page has been drawn — or laid out to nothing, which is a page the reader cannot be
         * shown but must still be able to leave. The chrome waits for this rather than for [pages],
         * because pages exist one frame before anything is on the glass.
         */
        val pagePainted: Boolean = false,
        val tocVisible: Boolean = false,
        /** The drawer's rows, parsed when the drawer is first opened rather than when the book is. */
        val chapters: List<Pair<Int, String>> = emptyList(),
        val settingsVisible: Boolean = false,
        /** Set while an imported font is being copied and checked; the panel shows it as such. */
        val fontBusy: Boolean = false,
        /** Why the last font import did not take, if it did not. */
        val fontError: String? = null,
        /**
         * The typeface the text is drawn and measured in, loaded from [ReaderSettings.fontPath].
         *
         * Carried in the state rather than fetched by the screen because loading a 10 MB CJK font is
         * file I/O — it happens on the layout's own background pass, once per path, and both the
         * paginator and the view read the same object afterwards.
         */
        val typeface: Typeface? = null,
        val openedMillis: Long = 0,
        val openedFromCache: Boolean = false,
        val charset: String = "",
    ) {
        val pageCount: Int get() = pages.size
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Kept out of [State] because it is megabytes of text, not a value to compare on. */
    private var book: OpenedTxtBook? = null
    private var widthPx = 0
    private var heightPx = 0
    private var density = 1f
    private var fontScale = 1f

    /** The downloaded file this book is being read from: what a synced position names. */
    private var fileId: String = ""
    private var sha256: String = ""

    /** The current chapter's offset in the document, remembered while that chapter is open. */
    private var chapterStartCache: Pair<Int, Int>? = null

    /** True when the resume position came off the percentage rather than a matching anchor. */
    private var resumeFromPercent = false

    /** What Room holds, so a layout that did not move the reader does not write to it. */
    private var savedPosition: Pair<Int, Int>? = null

    /** The newest layout asked for; an older one finishes measuring without publishing its pages. */
    private var layoutRequest = 0

    /** True from the moment an open is asked for until its first layout has published. */
    private var opening = false

    /** One automatic download per requested open — see [downloadThenOpen]. */
    private var autoDownloadTried = false

    /** A layout that arrived before the view had a size, run by [setSize] the moment it does. */
    private var pendingLayout: Pair<Int, Int>? = null

    private var outlineLoaded = false
    private var outlineLoading = false

    init {
        // Installed for as long as this book is open, taken down with the screen: the relay is a
        // singleton, and a reader that has been popped off the back stack must not answer for the
        // shelf's volume keys.
        volumeKeys.handler = ::onVolumeKey
        // Same lifetime, same reason: while this book is on the glass its stored position belongs
        // to the reader, and a background sync must not move it — see [OpenBook].
        openBook.bookId = bookId
        viewModelScope.launch {
            settings.reader.collect { next ->
                val previous = geometryBaseline
                geometryBaseline = next
                _state.update { it.copy(settings = next) }
                // The first emission is the current value, not a change. Treating it as one made the
                // collector re-layout from the state's placeholder settings before `open()` had
                // restored the saved position, which moved the reader off their page on reopen.
                if (previous == null) return@collect
                // Everything else travels to the view through the state; only a change of geometry is
                // worth measuring the chapter again for. The guard here was the other way round, so
                // every knob that *did* change the geometry — font size, line height, an imported
                // font — left the pages measured for the old one: the label on the bar changed and
                // the page did not, and a font import looked like it had done nothing at all.
                if (previous.sameGeometry(next)) return@collect
                relayout(currentOffset())
            }
        }
        open()
    }

    private var geometryBaseline: ReaderSettings? = null

    /** The screen's entry point, and the 重试 button's: a fresh request may download again. */
    fun open() {
        autoDownloadTried = false
        openInternal()
    }

    private fun openInternal() {
        opening = true
        // Started on IO rather than on the main thread: the parse finishes while the reader screen
        // is still composing its first frame, and a continuation that has to be dispatched back to
        // main simply queues behind that frame — 42 ms on the emulator, spent waiting, on a path
        // whose whole budget is 150. The state it publishes is a StateFlow, so the composition
        // picks the value up at its next frame instead of the open blocking on it.
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(loading = true, error = null, waiting = null, chapters = emptyList(), pagePainted = false)
            }
            outlineLoaded = false
            outlineLoading = false
            // The stored settings, read here rather than waited for from the collector: the open is
            // what needs them, and the collector's first emission carries no relayout of its own. A
            // layout measured against the state's placeholder (19 sp on a reader who set 29) breaks
            // every line for a font nobody is looking at — the page then draws its text at the real
            // size and the lines run off the right edge, because the wrap points were never the
            // reader's.
            val persisted = settings.reader.first()
            _state.update { it.copy(settings = persisted) }
            val started = SystemClock.elapsedRealtime()
            openStartedAt = started
            // One call, one query, one coroutine: the book and the reader's place in it come from
            // the same row, so there is nothing left to run in parallel with.
            reader.open(bookId).fold(
                onSuccess = { open ->
                    book = open.book
                    fileId = open.fileId
                    sha256 = open.sha256
                    resumeFromPercent = open.resumeFromPercent
                    // The lap that closes the open itself. It used to hold the hop back to the main
                    // thread — 42 ms, spent waiting for the reader screen's first frame to finish
                    // composing — and now holds nothing: the trace reads as parse, then layout.
                    open.stages.lap("handoff")
                    val saved = open.resume
                    savedPosition = saved?.let { it.chapterIndex to it.charOffset }
                    openTrace = OpenTrace(started, open.tookMillis, open.fromCache, open.stages)
                    _state.update {
                        it.copy(
                            loading = false,
                            title = open.title,
                            chapterCount = open.book.chapterCount,
                            chapterIndex = (saved?.chapterIndex ?: 0).coerceIn(0, open.book.chapterCount - 1),
                            // Seeded here rather than only in layoutChapter: the view may not have a
                            // size yet, in which case that call returns without doing anything, and
                            // the first real layout would then start from 0 and send the reader back
                            // to the top of the chapter they had just reopened.
                            anchorOffset = saved?.charOffset ?: 0,
                            openedMillis = open.tookMillis,
                            openedFromCache = open.fromCache,
                            charset = open.charset,
                        )
                    }
                    layoutChapter(_state.value.chapterIndex, saved?.charOffset ?: 0)
                },
                onFailure = { failure ->
                    if (failure is UnreadableBook &&
                        failure.reason == UnreadableReason.NOT_ON_DEVICE &&
                        !autoDownloadTried
                    ) {
                        downloadThenOpen()
                    } else {
                        failOpen(failure.message ?: "无法打开这本书", failure)
                    }
                },
            )
        }
    }

    /**
     * Fetch the book the reader just asked to read, then open it.
     *
     * The file list only comes from the server — the cached row keeps formats, not file ids — so this
     * is the point where "not on this device" needs a connection. It tried once and gave up rather
     * than queueing a request per failed open: a download that lands bytes the object store then
     * rejects would otherwise retry itself forever, one silent transfer per tap.
     */
    private fun downloadThenOpen() {
        autoDownloadTried = true
        _state.update { it.copy(loading = true, error = null, waiting = "这本书还没在手机上，正在下载…") }
        viewModelScope.launch(Dispatchers.IO) {
            val detail = library.detail(bookId).getOrElse {
                return@launch failOpen("这本书还没有下载到本机，现在也连不上服务器，没法替你下载。", it)
            }
            val file = detail.files.firstOrNull { it.format.equals("txt", ignoreCase = true) }
                ?: return@launch failOpen("这本书没有 TXT 版本，阅读器目前只支持 TXT。", null)
            offline.requestOffline(bookId, detail.book.title, file)
            val settled = offline.observeTask(file.id).first {
                it == null ||
                    it.state == DownloadTaskEntity.DONE ||
                    it.state == DownloadTaskEntity.FAILED ||
                    it.state == DownloadTaskEntity.CANCELLED
            }
            if (settled?.state == DownloadTaskEntity.DONE) return@launch openInternal()
            failOpen(
                when (settled?.state) {
                    DownloadTaskEntity.FAILED -> "下载没有成功。看看手机能不能连上服务器，再点重试。"
                    DownloadTaskEntity.CANCELLED -> "这个下载被取消了，去下载页重新下载。"
                    else -> "下载没有开始，再点一次重试。"
                },
                null,
            )
        }
    }

    private fun failOpen(message: String, cause: Throwable?) {
        openTrace = null
        opening = false
        _state.update { it.copy(loading = false, waiting = null, error = message) }
        if (cause != null) Log.w(TAG, "open failed", cause)
    }

    /** Called from the view's layout pass; a size change is a layout change, so it re-paginates. */
    fun setSize(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        fontScale: Float,
    ) {
        if (widthPx <= 0 || heightPx <= 0) return
        val unchanged =
            widthPx == this.widthPx && heightPx == this.heightPx && density == this.density && fontScale == this.fontScale
        this.widthPx = widthPx
        this.heightPx = heightPx
        this.density = density
        this.fontScale = fontScale
        if (!unchanged) relayout(currentOffset())
    }

    /**
     * The one place sp becomes px.
     *
     * The screen has to hand the same number to [ReaderView] that the layout was measured with: a
     * renderer that rounded differently would draw text at a size whose advances disagree with the
     * line breaks, and the disagreement shows up as lines that stop short of the right margin.
     * `fontScale` is the user's system-wide text size, which `sp` means and `dp` does not.
     */
    fun fontSizePx(): Float = _state.value.settings.fontSizeSp * density * fontScale

    fun nextPage() = move(1)

    fun previousPage() = move(-1)

    /**
     * A page the *view* arrived at by itself — a tap in the left or right third. Recorded rather
     * than re-applied: the view already drew it, and pushing the value back through the update
     * block would be a round trip that changes nothing.
     */
    fun pageShown(index: Int) {
        val current = _state.value
        val page = current.pages.getOrNull(index) ?: return
        // The view echoing back the index the state already holds is not the reader turning a page;
        // recording it as one would re-quantize the anchor to that page's first character.
        if (index == current.pageIndex) return
        _state.update {
            it.copy(
                pageIndex = index,
                anchorOffset = page.firstChar,
                percent = bookPercent(current.chapterIndex, page.firstChar).toFloat(),
            )
        }
        savePosition(current.chapterIndex, page.firstChar)
    }

    fun jumpToChapter(index: Int) {
        val count = book?.chapterCount ?: return
        if (index !in 0 until count || index == _state.value.chapterIndex) return
        viewModelScope.launch {
            _state.update { it.copy(chapterIndex = index, tocVisible = false) }
            layoutChapter(index, 0)
        }
    }

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun hideChrome() = _state.update { it.copy(chromeVisible = false) }

    fun showSettings(visible: Boolean) = _state.update { it.copy(settingsVisible = visible, fontError = null) }

    /**
     * The volume keys, when the reader has asked for them.
     *
     * Answered `false` for every state in which a volume press means volume: the chrome is up (the
     * reader is looking at controls, not at the page), a drawer covers the page, or the book is still
     * loading. The plan's rule is exactly this — swallow the key only when the setting is on *and*
     * the page is the only thing on the glass — because a reader who cannot turn the volume down is
     * a reader with a bug report, and the setting is what makes that a choice rather than a defect.
     */
    private fun onVolumeKey(
        keyCode: Int,
        action: Int,
    ): Boolean {
        val current = _state.value
        if (!current.settings.volumeKeys) return false
        if (current.loading || current.error != null) return false
        if (current.chromeVisible || current.tocVisible || current.settingsVisible) return false
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        // The press is answered once, on the way down; the up event is swallowed without a second
        // turn. Swallowing it rather than letting it fall through is what keeps the system from
        // deciding the press was a volume press and showing the panel.
        if (action != KeyEvent.ACTION_DOWN) return true
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) nextPage() else previousPage()
        return true
    }

    override fun onCleared() {
        volumeKeys.handler = null
        // Handed back with the screen, and only if it is still this book's: a reader who opens a
        // second book before the first is collected must not have the newer one's claim erased by
        // the older one's teardown.
        if (openBook.bookId == bookId) openBook.bookId = null
    }

    /**
     * Opens or closes the drawer, parsing its rows the first time it is asked for.
     *
     * Indenting in the label rather than passing a level out means the drawer cannot render a
     * structure the document does not have — a splitter that found one flat list of chapters shows
     * one flat list, which is the honest picture of a book whose headings it could not order.
     */
    fun showToc(visible: Boolean) {
        _state.update { it.copy(tocVisible = visible) }
        if (visible) loadOutline()
    }

    /**
     * Parses the chapter list off the main thread.
     *
     * The list is no longer parsed by the open — 171 ms of the 473 ms reopen measured on the
     * emulator, spent on titles nobody had asked to see — so the drawer is where that cost lands, and
     * it lands here rather than in a frame. Kept afterwards, because the drawer is opened more than
     * once.
     */
    private fun loadOutline() {
        val opened = book ?: return
        if (outlineLoaded || outlineLoading) return
        outlineLoading = true
        viewModelScope.launch {
            val entries = withContext(Dispatchers.Default) {
                opened.outline().map { ref ->
                    ref.index to "  ".repeat((ref.level - 1).coerceAtLeast(0)) + ref.title
                }
            }
            outlineLoading = false
            outlineLoaded = true
            _state.update { it.copy(chapters = entries) }
        }
    }

    /** Writes the position unless Room already holds exactly this one, and says whether it wrote. */
    private fun savePosition(
        chapterIndex: Int,
        charOffset: Int,
    ): Boolean {
        val position = chapterIndex to charOffset
        // A reopen reads the position and then lays the same chapter out at the same offset; writing
        // back what was just read cost 28 ms of the budget and changed nothing but `updatedAt`.
        if (position == savedPosition) return false
        savedPosition = position
        // The percentage is computed here because this is the only place that knows the chapter's
        // offset in the document — the row itself is chapter-relative, and a row that cannot say how
        // far through the book it is cannot be merged against another device's, or survive the file
        // being replaced.
        val percent = bookPercent(chapterIndex, charOffset)
        viewModelScope.launch { reader.savePosition(bookId, chapterIndex, charOffset, percent, fileId, sha256) }
        return true
    }

    /**
     * How far through the book a chapter-relative offset is, in percent of the whole document.
     *
     * The chapter's start is asked of the opener once per chapter and remembered until the reader
     * moves to another one: walking the chapter list to find row 3 000 is a scan of the list's
     * bytes, and this runs on every page turn.
     */
    private fun bookPercent(
        chapterIndex: Int,
        charOffset: Int,
    ): Double {
        val opened = book ?: return 0.0
        val total = opened.normalizedChars
        if (total <= 0) return 0.0
        val cached = chapterStartCache
        val start =
            if (cached != null && cached.first == chapterIndex) {
                cached.second
            } else {
                val found = opened.chapterStart(chapterIndex) ?: return 0.0
                chapterStartCache = chapterIndex to found
                found
            }
        return ((start + charOffset).toDouble() / total * 100.0).coerceIn(0.0, 100.0)
    }

    fun adjustFontSize(deltaSp: Float) = updateSettings {
        it.copy(fontSizeSp = (it.fontSizeSp + deltaSp).coerceIn(MIN_FONT_SP, MAX_FONT_SP))
    }

    fun adjustLineHeight(delta: Float) = updateSettings {
        it.copy(lineHeightMultiplier = (it.lineHeightMultiplier + delta).coerceIn(1.2f, 2.4f))
    }

    fun cyclePalette() = setPalette(PALETTES[(PALETTES.indexOf(_state.value.settings.palette).coerceAtLeast(0) + 1) % PALETTES.size])

    fun setPalette(id: String) = updateSettings { it.copy(palette = id) }

    fun setMotion(id: String) = updateSettings { it.copy(motion = id) }

    /** The window brightness for the reader screen alone; [ReaderSettings.BRIGHTNESS_SYSTEM] frees it. */
    fun setBrightness(value: Float) = updateSettings {
        it.copy(brightness = if (value < 0f) ReaderSettings.BRIGHTNESS_SYSTEM else value.coerceIn(MIN_BRIGHTNESS, 1f))
    }

    fun setWarmth(value: Float) = updateSettings { it.copy(warmth = value.coerceIn(0f, 1f)) }

    fun setVolumeKeys(enabled: Boolean) = updateSettings { it.copy(volumeKeys = enabled) }

    /** Back to the platform typeface; the file stays on disk, since another book may want it back. */
    fun useDefaultFont() = updateSettings { it.copy(fontPath = null) }

    /**
     * Takes a font the reader picked and makes it the reader's typeface.
     *
     * Copied into `filesDir` rather than read through the returned `content://` URI: a persisted
     * grant can be revoked and a file on a card can be unmounted, and a reader whose page failed to
     * lay out because the font went away is a worse outcome than 12 MB of private storage.
     *
     * The copy is verified by *loading* it before it becomes a setting. A file that the picker was
     * willing to show but that `createFromFile` rejects — a `.ttc` collection, an OTF variant the
     * platform declines, or anything at all renamed to `.ttf` — would otherwise be accepted, fail at
     * the first layout, and show the reader a page laid out for a font nobody can see.
     */
    fun importFont(uri: Uri) {
        _state.update { it.copy(fontBusy = true, fontError = null) }
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) { copyFont(uri) }
            _state.update { it.copy(fontBusy = false) }
            imported.fold(
                onSuccess = { file ->
                    // The old import goes only after the new one is on disk and loadable, so a failed
                    // import cannot leave the reader with no font at all.
                    withContext(Dispatchers.IO) { pruneFonts(keep = file) }
                    updateSettings { it.copy(fontPath = file.absolutePath) }
                },
                onFailure = { failure ->
                    _state.update { it.copy(fontError = failure.message ?: "这个文件不能用作字体") }
                },
            )
        }
    }

    private fun copyFont(uri: Uri): Result<File> = runCatching {
        val dir = File(appContext.filesDir, FONT_DIR).apply { mkdirs() }
        // Named by content, so importing the same font twice is the same file — and so a *different*
        // font lands on a different path, which is what makes the settings change a layout change.
        val digest = MessageDigest.getInstance("SHA-256")
        var pending = File(dir, "pending.font")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            pending.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("这个文件打不开")
        val named = File(dir, "imported-${digest.digest().take(4).joinToString("") { "%02x".format(it) }}.ttf")
        if (named != pending) {
            named.delete()
            if (!pending.renameTo(named)) {
                // Windows-style rename refusals do not exist here, but a cross-device move would:
                // copying once more is cheaper than giving up on a font the reader just chose.
                pending.copyTo(named, overwrite = true)
                pending.delete()
            }
        }
        pending = named
        // Loading is the check: an extension and a MIME type are claims, and this is the thing itself.
        val loaded = runCatching { Typeface.createFromFile(named) }
        if (loaded.isFailure) {
            named.delete()
            Log.w(TAG, "font $uri will not load", loaded.exceptionOrNull())
            error("这个文件不能用作字体")
        }
        named
    }

    /** Keeps one imported font; a reader who tries six faces should not keep all six on disk. */
    private fun pruneFonts(keep: File) {
        File(appContext.filesDir, FONT_DIR).listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    private fun updateSettings(change: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settings.updateReader(change) }
    }

    private fun move(delta: Int) {
        val current = _state.value
        val chapterCount = book?.chapterCount ?: return
        val target = current.pageIndex + delta
        when {
            target in current.pages.indices -> {
                _state.update {
                    it.copy(
                        pageIndex = target,
                        anchorOffset = current.pages[target].firstChar,
                        percent = bookPercent(current.chapterIndex, current.pages[target].firstChar).toFloat(),
                    )
                }
                // A turn that did not come from the view — the volume keys — has no `pageShown` call
                // to record it: the view's echo of the new index is recognised as an echo and
                // deliberately not treated as a page turn, so a keyboard-driven page would move on
                // screen and be forgotten on exit.
                savePosition(current.chapterIndex, current.pages[target].firstChar)
            }

            delta > 0 && current.chapterIndex + 1 < chapterCount -> viewModelScope.launch {
                _state.update { it.copy(chapterIndex = current.chapterIndex + 1) }
                layoutChapter(current.chapterIndex + 1, 0)
            }

            delta < 0 && current.chapterIndex > 0 -> viewModelScope.launch {
                _state.update { it.copy(chapterIndex = current.chapterIndex - 1) }
                // Coming in through the front door means landing on the last page, which is only
                // knowable after the chapter is laid out — hence a saturating offset, not a guess.
                layoutChapter(current.chapterIndex - 1, Int.MAX_VALUE)
            }
        }
    }

    private fun relayout(offset: Int) {
        val pending = pendingLayout
        pendingLayout = null
        // The size and the opened book race, and either can win: measured on the emulator the query
        // and the view's first measure both land ~20 ms after the reader taps the book. When the
        // size wins, the request that arrived before it is the open's own — the one that knows where
        // the reader was — so it is the one that runs.
        if (pending != null) return layoutNow(pending.first, pending.second)
        // While an open is in flight the reader's position is the one the open read and it is not in
        // the state yet, so a layout started from here would paginate from the placeholder anchor and
        // be superseded a moment later by the open's own: the same chapter measured twice, with only
        // the second one ever published. Measured cold, that second start arrived 24 ms after the
        // first and pushed the publish 65 ms later. The open's layout reads the size when it runs,
        // so nothing is lost by letting it go first.
        if (opening) return
        layoutNow(_state.value.chapterIndex, offset)
    }

    private fun layoutNow(
        chapterIndex: Int,
        offset: Int,
    ) {
        viewModelScope.launch { layoutChapter(chapterIndex, offset) }
    }

    private suspend fun layoutChapter(
        chapterIndex: Int,
        offset: Int,
        // While an open is in flight every layout belongs to it — including the one the view's first
        // measured size triggers, because the pages the reader ends up seeing may well be those.
        stages: StageTimer? = openTrace?.stages,
    ) {
        val opened = book ?: return
        if (widthPx == 0 || heightPx == 0) {
            // The size is not a constant the layout can assume: without it there is no page width to
            // break lines against, so the request is remembered rather than dropped. Dropping it is
            // how a reopen ended up with no layout at all when the book came back before the view
            // had been measured.
            pendingLayout = chapterIndex to offset
            return
        }
        // Measuring a chapter costs 25–45 ms (25 on the emulator's cache-warm path, 45 cold), and it
        // used to be spent on the main thread after the reader screen's first frame — the one window
        // where the thread was the only thing the page was waiting for. Sent to Default it overlaps
        // that frame's own draw instead: the state still arrives on the main thread, but as a value
        // that is already computed rather than as the start of 40 ms of work.
        val request = ++layoutRequest
        var chapterTitle = ""
        var face: Typeface? = null
        val layout = withContext(Dispatchers.Default) {
            val chapter: Chapter = opened.chapterAt(chapterIndex)
            stages?.lap("chapter")
            chapterTitle = chapter.title
            val reader = _state.value.settings
            val fontSizePx = fontSizePx()
            val marginPx = reader.marginDp * density
            // The face is loaded here, not where the setting is read, because loading it is file I/O
            // on a 10 MB CJK font — and because the paginator has to measure with *this* face, not
            // with whichever one the view happens to be drawing. Measuring with one font and drawing
            // with another breaks every line at a character the reader's font does not break at.
            face = loadTypeface(reader.fontPath)
            Paginator.paginate(
                chapter,
                PageSpec(
                    pageWidth = widthPx.toFloat(),
                    pageHeight = heightPx.toFloat(),
                    fontSize = fontSizePx,
                    marginTop = marginPx,
                    marginBottom = marginPx,
                    marginStart = marginPx,
                    marginEnd = marginPx,
                    lineHeightMultiplier = reader.lineHeightMultiplier,
                ),
                AndroidGlyphMeasurer(fontSizePx, face ?: Typeface.DEFAULT),
            ).also { stages?.lap("paginate") }
        }
        // Two layouts can now be in flight at once — a size change during an open, a knob turned
        // while a chapter is being measured — so only the newest one is allowed to publish. Dropping
        // the older result is what keeps the anchor from being re-resolved against pages that the
        // settings have already moved on from.
        if (request != layoutRequest) return
        val page = Paginator.pageIndexFor(layout.pages, offset)
        // The reader's position is the offset they were looking at, *not* the first character of the
        // page that contains it. Re-quantizing to the page start made every font-size step jump
        // backwards to the top of its page — 357, 331, 299, 0 across three taps on the emulator —
        // which is the exact bug the anchor exists to prevent.
        val anchorChar = if (offset >= layout.charCount) layout.pages.last().firstChar else offset
        _state.update {
            it.copy(
                pages = layout.pages,
                text = layout.text,
                pageIndex = page,
                anchorOffset = anchorChar,
                chapterIndex = chapterIndex,
                chapterTitle = chapterTitle,
                typeface = face,
                // The book's percentage, not the chapter's: the label on the bar reads "how far
                // through the novel", and a chapter fallback of 0–100 % per chapter made the same
                // page read 3 % in chapter 1 and 3 % again in chapter 90.
                percent = bookPercent(chapterIndex, anchorChar).toFloat(),
                // A chapter that laid out to nothing will never paint a page, and the chrome is the
                // reader's only way back out of one.
                pagePainted = it.pagePainted || layout.pages.isEmpty(),
            )
        }
        if (savePosition(chapterIndex, anchorChar)) stages?.lap("save")
        finishOpenTrace()
    }

    /**
     * The stopwatch the acceptance number comes from, running until a page is on screen.
     *
     * Logged by the first layout that produced pages rather than at the end of [open], because when
     * the view has no size yet the pages arrive from the size-driven relayout instead — and a number
     * reported from the other path would be a number for a screen that is still blank.
     */
    private class OpenTrace(
        val startedAt: Long,
        val parseMillis: Long,
        val fromCache: Boolean,
        val stages: StageTimer,
    )

    private var openTrace: OpenTrace? = null

    /** When the reader asked for this book; the origin of both stopwatches below. */
    private var openStartedAt = 0L
    private var frameLoggedAt = -1L

    private fun finishOpenTrace() {
        opening = false
        val trace = openTrace ?: return
        openTrace = null
        val current = _state.value
        Log.i(
            TAG,
            "open parse=${trace.parseMillis}ms fromCache=${trace.fromCache} " +
                "${SystemClock.elapsedRealtime() - trace.startedAt}ms to first page, " +
                "chapter ${current.chapterIndex + 1}/${current.chapterCount}, ${current.pageCount} pages, " +
                "${current.text.length} chars, resume=${if (resumeFromPercent) "percent" else "anchor"} " +
                "| ${trace.stages}",
        )
    }

    /**
     * The reading surface painted a page: the first evidence that the open is actually over.
     *
     * [SystemClock] rather than the trace's clock, because this one is stamped from the draw pass
     * while the trace's laps all precede it — the gap between the two *is* the frame the reader is
     * looking at, so it is worth being able to see.
     */
    fun firstTextFrameDrawn() {
        // Stamped from the draw pass, which is the one place that knows a page — not merely pages —
        // is on the glass. The chrome waits for this rather than for the layout, and lands on the
        // frame after this one.
        if (!_state.value.pagePainted) _state.update { it.copy(pagePainted = true) }
        val base = openStartedAt
        if (base == 0L || frameLoggedAt == base) return
        frameLoggedAt = base
        Log.i(
            TAG,
            "first page on screen ${SystemClock.elapsedRealtime() - base}ms after the reader was asked for the book",
        )
    }

    /** The offset the reader is looking at; the anchor every re-layout is re-resolved against. */
    private fun currentOffset(): Int = _state.value.anchorOffset

    private var fontPathLoaded: String? = null
    private var fontLoaded: Typeface? = null

    /** Loads the imported font once per path; a path that will not load is the platform typeface. */
    private fun loadTypeface(path: String?): Typeface? {
        if (path == null) return null
        if (path == fontPathLoaded) return fontLoaded
        fontPathLoaded = path
        val loaded = runCatching { Typeface.createFromFile(path) }
        if (loaded.isFailure) Log.w(TAG, "font $path is gone; the platform face stands in", loaded.exceptionOrNull())
        fontLoaded = loaded.getOrNull()
        return fontLoaded
    }

    private fun ReaderSettings.sameGeometry(other: ReaderSettings) =
        fontSizeSp == other.fontSizeSp &&
            lineHeightMultiplier == other.lineHeightMultiplier &&
            marginDp == other.marginDp &&
            // A different font is a different set of advances, so it is geometry: the page has to be
            // measured again, not merely redrawn. Depth of the palette, warmth and brightness are not
            // — they change pixels, and redrawing covers them.
            fontPath == other.fontPath

    private companion object {
        const val TAG = "EbookdReader"
        const val DEFAULT_FONT = 19f
        const val MIN_FONT_SP = 12f
        const val MAX_FONT_SP = 44f

        /** The order the quick bar's palette button cycles through, and the panel's four choices. */
        val PALETTES = listOf("day", "sepia", "night", "pure")

        /** Below this the page is unreadable and the reader has lost their book in the dark. */
        const val MIN_BRIGHTNESS = 0.05f

        const val FONT_DIR = "fonts"
    }
}

fun paletteFor(id: String): ReaderPalette = when (id) {
    "night" -> ReaderPalette.Night
    "pure" -> ReaderPalette.PureBlack
    "sepia" -> ReaderPalette.Sepia
    else -> ReaderPalette.Day
}

fun motionFor(id: String): PageMotion = when (id) {
    "none" -> NoneMotion()
    "cover" -> CoverMotion()
    else -> SlideMotion()
}
