package dev.ebookd.core.text

import dev.ebookd.core.storage.ObjectStore
import dev.ebookd.engine.core.CharsetDetector
import dev.ebookd.engine.core.Chapter
import dev.ebookd.engine.core.ChapterMark
import dev.ebookd.engine.core.SplitMode
import dev.ebookd.engine.core.SplitOptions
import dev.ebookd.engine.core.TextNormalizer
import dev.ebookd.engine.core.TocCodec
import dev.ebookd.engine.core.TxtChapterSplitter
import dev.ebookd.engine.core.TxtDocumentBuilder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Opens a stored TXT, and does the expensive part only once.
 *
 * The first open decodes, normalizes and splits into chapters; the result is two files keyed by the
 * **source** hash — the normalized text, and the chapter list encoded with [TocCodec] — so reopening
 * reads a file and a list instead of running the heuristics again.
 *
 * The key is the hash of the bytes rather than the book id, because a replaced file must not
 * inherit a stale chapter list: the offsets would still point inside the text, and the reader would
 * be sent to a plausible-looking wrong paragraph instead of being told the cache was stale.
 *
 * Nothing here builds blocks, and nothing here reads a chapter it was not asked for. Splitting is
 * what *guesses* — heading detection, the TOC region, the numbering cross-check — and a guess is
 * worth caching because its answer moves with the algorithm version, which is what [PIPELINE_VERSION]
 * retires on change. Blocks are mechanical and per chapter, so [OpenedTxtBook.chapterAt] makes one
 * when the page that needs it is drawn; the chapter list itself is checked but not parsed, so a
 * reader opening chapter 3 000 pays for one row rather than 9 500.
 *
 * The cached text is **UTF-16LE**, which is what makes that lazy read possible without a separate
 * index: in UTF-16LE a character offset times two *is* a byte offset, so the chapter list's
 * character ranges become `seek` arguments as they stand. It costs disk — a Chinese novel doubles in
 * size — and buys an open that reads the few thousand characters of one chapter instead of all
 * 20 MB. That is the trade the plan's "reopening must feel instant" line asks for, and disk is the
 * cheap side of it.
 */
class TxtBookOpener(private val objects: ObjectStore) {

    private val cacheRoot = File(objects.root, "txt/v$PIPELINE_VERSION")

    /** What an open holds: the chapter list's shape, the text's length, and how to read a range. */
    internal class Cache(
        val toc: TocCodec.Stored,
        val chars: Int,
        val readRange: (start: Int, end: Int) -> String,
    )

    /** One row of the chapter list, as the drawer shows it: a name and the range it covers. */
    data class ChapterRef(
        val index: Int,
        val title: String,
        val level: Int,
        val charStart: Int,
        val charEnd: Int,
    )

    private class Built(
        val charset: String,
        val cache: Cache,
    )

    /**
     * A book that is open but not yet laid out.
     *
     * [chapterAt] reads one range of the cached text and parses one row of the chapter list, so a
     * 20 MB novel with 9 500 chapters costs neither the memory of all of them nor the 368 ms that
     * turning the whole file into a `String` took when the open read it end to end.
     *
     * Nested rather than `inner` on purpose, and not for style: the Compose compiler runs over this
     * module and writes a `$stable` field into the classes it sees — except `inner` ones, which it
     * leaves alone. `ReaderRepository.Readable` is such a class and holds one of these, so its own
     * `$stable` initialiser reads `OpenedTxtBook.$stable`; with an `inner` declaration that field is
     * absent and the read throws `NoSuchFieldError` while the reader is opening a book. The rebuild
     * the repair path needs therefore arrives as a lambda instead of as a captured outer instance.
     */
    class OpenedTxtBook internal constructor(
        val title: String,
        val charset: String,
        val fromCache: Boolean,
        val tookMillis: Long,
        private val rebuildCache: () -> Cache,
        private var cache: Cache,
    ) {
        val normalizedChars: Int get() = cache.chars

        val chapterCount: Int get() = cache.toc.lineCount

        fun chapterAt(index: Int): Chapter {
            val row = cache.toc.chapter(index)
                ?: repair().toc.chapter(index)
                ?: error("chapter $index is unreadable, and rebuilding the cache did not produce it")
            val text = cache.readRange(row.charStart, row.charEnd)
            // Re-rooted at zero: the paginator's offsets index one chapter's text, and a
            // document-wide offset would point outside the string it was handed.
            return TxtDocumentBuilder.chapter(
                text,
                TxtDocumentBuilder.ChapterSlice(row.title, row.level, 0, text.length),
                index,
            )
        }

        /**
         * The chapter's offset in the normalized document — what a whole-book percentage needs.
         *
         * [chapterAt] deliberately re-roots a chapter at zero, because the paginator's offsets
         * index one chapter's text; this is the other coordinate, the one the document itself is
         * measured in. It is the only way to say "62 % of the way through" without depending on a
         * layout, a font, or the page the reader happens to be on. One row is walked and parsed, so
         * the price is that row rather than the nine thousand above it.
         */
        fun chapterStart(index: Int): Int? =
            (cache.toc.chapter(index) ?: repair().toc.chapter(index))?.charStart

        /**
         * The drawer's contents and the ranges they point at: every row, parsed on first use.
         *
         * Kept, because a book's list does not change and the drawer is opened more than once. The
         * first caller pays for 9 500 rows, so it should be somebody other than the main thread.
         */
        fun outline(): List<ChapterRef> = outlineRows.value

        private val outlineRows = lazy {
            val rows = cache.toc.rows() ?: repair().toc.rows()
            rows.orEmpty().map { ChapterRef(it.index, it.title, it.level, it.charStart, it.charEnd) }
        }

        /**
         * Rebuilds the cache from the stored object after a row turned out not to be one.
         *
         * Reading a row at a time moves the check from the open to the read, so the rebuild moves
         * with it. The outcome is the one the eager reader had — a cache that cannot be read is
         * replaced, once — except that it now happens for the row that was actually wrong instead
         * of for every open.
         */
        private fun repair(): Cache {
            cache = rebuildCache()
            return cache
        }
    }

    fun open(
        title: String,
        sha256: String,
        stages: StageTimer = StageTimer(),
    ): OpenedTxtBook {
        val started = System.nanoTime()
        val objectFile = objects.pathFor(sha256)
        require(objectFile.isFile) { "no stored object for sha $sha256" }
        stages.lap("locate")

        val textFile = File(cacheRoot, "$sha256.u16.txt")
        val tocFile = File(cacheRoot, "$sha256.toc.txt")
        // Handed to the book so that a row that will not read can be answered by rebuilding the
        // cache — the same two files, from the same source — long after this call has returned.
        val rebuild = { buildFromSource(objectFile, textFile, tocFile, stages = null).cache }

        readCache(textFile, tocFile, stages)?.let { cache ->
            return OpenedTxtBook(
                title = title,
                // The cache holds normalized text, which is UTF-16LE by definition; the source
                // charset was the answer to a question that has already been settled.
                charset = "UTF-8",
                fromCache = true,
                tookMillis = elapsed(started),
                rebuildCache = rebuild,
                cache = cache,
            )
        }

        val built = buildFromSource(objectFile, textFile, tocFile, stages)
        return OpenedTxtBook(
            title = title,
            charset = built.charset,
            fromCache = false,
            tookMillis = elapsed(started),
            rebuildCache = rebuild,
            cache = built.cache,
        )
    }

    /**
     * The cold path: bytes to a normalized text, a chapter list and a cache on disk.
     *
     * Shared with the repair path, which is what makes a cache that cannot be read no different
     * from one that is not there — both end up here, and both leave the same two files behind.
     */
    private fun buildFromSource(
        objectFile: File,
        textFile: File,
        tocFile: File,
        stages: StageTimer?,
    ): Built {
        val detected = CharsetDetector.detect(objectFile.readBytes())
        stages?.lap("decode-bytes")
        val normalized = TextNormalizer.normalizeText(detected.text)
        stages?.lap("normalize")
        val split = TxtChapterSplitter.split(normalized, SplitOptions(mode = SplitMode.AUTO))
        stages?.lap("split")
        val slices = ranges(split.marks.sortedBy { it.startChar }, normalized.length)
        stages?.lap("ranges")
        val encoded = TocCodec.encode(
            slices.mapIndexed { index, slice ->
                // `Chapter` with no blocks is the shape the codec stores: offsets and names are the
                // whole payload, and the blocks come back from the text.
                Chapter(index, slice.title, slice.level, charStart = slice.charStart, charEnd = slice.charEnd)
            },
            normalized.length,
        )
        val written = writeCache(textFile, tocFile, normalized, encoded)
        stages?.lap("write")
        val stored = checkNotNull(TocCodec.inspect(encoded, normalized.length)) {
            "the toc this codec just wrote does not read back"
        }
        // A failed write leaves this open reading from the text it already holds; only the next open
        // re-splits. Losing the cache is not a reason to lose the book being read.
        return Built(
            charset = detected.charset,
            cache = Cache(
                toc = stored,
                chars = normalized.length,
                readRange = if (written) { start, end -> readRange(textFile, start, end) }
                else { start, end -> normalized.substring(start, end) },
            ),
        )
    }

    /** Each chapter ends where the next begins, so the ranges tile the document without gaps. */
    private fun ranges(
        marks: List<ChapterMark>,
        length: Int,
    ): List<TxtDocumentBuilder.ChapterSlice> = marks.mapIndexed { index, mark ->
        TxtDocumentBuilder.ChapterSlice(
            title = mark.title,
            level = mark.level,
            charStart = mark.startChar,
            charEnd = marks.getOrNull(index + 1)?.startChar ?: length,
        )
    }

    /** Null when either half is missing, or when the toc is not a list this codec wrote. */
    private fun readCache(
        textFile: File,
        tocFile: File,
        stages: StageTimer,
    ): Cache? {
        val usable = textFile.isFile && tocFile.isFile && textFile.length() % BYTES_PER_CHAR == 0L
        stages.lap("stat")
        if (!usable) return null
        // The length comes from the file's own size and the codec checks its trailer against it, so
        // a truncated, swapped or half-written cache reads as a miss rather than as a book whose
        // chapters point at the wrong paragraphs.
        val chars = (textFile.length() / BYTES_PER_CHAR).toInt()
        val toc = runCatching { tocFile.readText() }.getOrNull()
        stages.lap("read")
        if (toc == null) return null
        val stored = TocCodec.inspect(toc, chars)
        stages.lap("inspect")
        // A book with no chapters cannot be read at all, and a list with no rows is not a cache of
        // anything: both are treated as a miss so the source gets re-split.
        if (stored == null || stored.lineCount == 0) return null
        return Cache(stored, chars) { start, end -> readRange(textFile, start, end) }
    }

    /**
     * The chapter list is the one file that must never be *partly* present: a toc whose last line
     * was cut off still decodes, still ascends, and silently drops the end of the book. So it is
     * written under another name and moved into place, and the text goes first — a crash before the
     * move leaves no toc at all, which [readCache] reports as a miss and the next open rebuilds.
     *
     * The move replaces, rather than plain `renameTo`: that is what a *repair* needs, since the
     * target is by definition already there, and on Windows `renameTo` refuses an existing
     * destination — which turned every repair into a cache the files disagreed about.
     *
     * Returns whether the cache is now usable, so the caller knows whether to read from disk or from
     * the text it already holds.
     */
    private fun writeCache(
        textFile: File,
        tocFile: File,
        text: String,
        encoded: String,
    ): Boolean {
        cacheRoot.mkdirs()
        val pending = File(cacheRoot, "${tocFile.name}.part")
        val written = runCatching {
            textFile.writeText(text, Charsets.UTF_16LE)
            pending.writeText(encoded)
            Files.move(pending.toPath(), tocFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val cached = written.isSuccess
        pending.delete()
        // A half cache is worse than none: the text would sit there unusable, and the next open
        // could not tell it apart from a complete one.
        if (!cached) textFile.delete()
        return cached
    }

    private fun readRange(
        file: File,
        start: Int,
        end: Int,
    ): String {
        val chars = end - start
        if (chars <= 0) return ""
        return RandomAccessFile(file, "r").use { handle ->
            handle.seek(start.toLong() * BYTES_PER_CHAR)
            val bytes = ByteArray(chars * BYTES_PER_CHAR)
            handle.readFully(bytes)
            String(bytes, Charsets.UTF_16LE)
        }
    }

    private fun elapsed(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    companion object {
        /** UTF-16LE: two bytes per `Char`, which is what makes a character offset seekable. */
        private const val BYTES_PER_CHAR = 2

        /** Bump whenever normalization or splitting would answer old input differently. */
        // v3: the charset probe used to call a >64 KB UTF-8 file GB18030, so every cache under v2
        // holds a mojibake copy of the book and no re-split would fix it.
        const val PIPELINE_VERSION = 3
    }
}
