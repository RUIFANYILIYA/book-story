package dev.ebookd.engine.core

/**
 * Serializes a table of contents — the chapter list the splitter produced — into one string and
 * back.
 *
 * This is what gets stored: the `chapters` rows are (index, title, level, charStart, charEnd), and
 * a cache that survives an app update has to be able to say *which codec wrote it*. Hence the
 * versioned header: [inspect] and [decode] return null for anything they cannot read exactly, and a
 * discarded cache costs one re-split, while a misread one would move every saved reading position.
 *
 * The format is line-based and human-readable on purpose. It is small (a few hundred lines for a
 * novel), it diffs sensibly in a test fixture, and its escaping is explicit rather than "titles
 * never contain tabs, surely".
 *
 * Reading is split in two, because opening a book needs one chapter and the drawer needs all of
 * them. [inspect] checks the file's ends and counts its rows; [Stored.chapter] parses one row; and
 * [Stored.rows] parses the lot. Decoding every row to open a book was the largest single cost of
 * reopening a cached one — 9 500 rows parsed so that four pages could be laid out from the third.
 */
object TocCodec {

    const val VERSION = 2

    private const val HEADER = "ebookd-toc/$VERSION"
    private const val TRAILER = "#"

    /**
     * `Chapter.blocks` is deliberately not encoded: the blocks are rebuilt from the normalized text
     * on open, and a cache that carried them would double the size of every write for something it
     * can reconstruct. Only the anchors and names travel.
     *
     * The document's length travels as a **trailer**, not in the header, so that cutting the file
     * anywhere — including mid-line — destroys the claim that the whole list is present.
     */
    fun encode(chapters: List<Chapter>, sourceChars: Int): String = buildString {
        appendLine(HEADER)
        for (chapter in chapters) {
            append(chapter.index)
            append('\t')
            append(chapter.level)
            append('\t')
            append(chapter.charStart)
            append('\t')
            append(chapter.charEnd)
            append('\t')
            append(escape(chapter.title))
            append('\n')
        }
        appendLine("$TRAILER$sourceChars")
    }

    /**
     * The list's shape, read from its two ends: the header, the trailer, and how many rows lie
     * between them.
     *
     * Rows are not parsed here. What is checked is what a partial write destroys — a file missing
     * its tail has no trailer, and one written by another version has no header — because those are
     * the corruptions this format exists to survive. A row that will not parse is found by whoever
     * reads it, and the reader rebuilds the cache when that happens.
     */
    fun inspect(
        encoded: String,
        sourceChars: Int,
    ): Stored? {
        if (encoded.length < HEADER.length + 2) return null
        // `encode` ends with a newline, so a complete file's last character is one.
        if (encoded[encoded.length - 1] != '\n') return null
        if (!encoded.startsWith(HEADER) || encoded[HEADER.length] != '\n') return null
        val trailerStart = encoded.lastIndexOf('\n', encoded.length - 2) + 1
        val trailer = encoded.substring(trailerStart, encoded.length - 1)
        if (!trailer.startsWith(TRAILER)) return null
        if (trailer.substring(TRAILER.length).toIntOrNull() != sourceChars) return null
        // The newline before the trailer belongs to the last row, which is why it ends the rows
        // rather than starting them.
        return Stored(encoded, sourceChars, HEADER.length + 1, (trailerStart - 1).coerceAtLeast(HEADER.length + 1))
    }

    /**
     * Returns null when the input is not exactly what [encode] wrote at this [VERSION] for a
     * document of [sourceChars] characters.
     *
     * Anchors are only trustworthy as a whole. A list whose last line was lost still parses, still
     * ascends and still lies inside the text — it just describes a shorter book, and a reader who
     * notices has lost the end of a novel with no explanation. The trailer is what turns that
     * silent outcome into a cache miss.
     */
    fun decode(
        encoded: String,
        sourceChars: Int,
    ): List<Chapter>? = inspect(encoded, sourceChars)?.rows()

    /**
     * A stored list that has been checked but not read.
     *
     * Holds the text rather than a parsed list, so a caller that wants one row pays for one row:
     * [chapter] walks to that row's line and parses it, and [rows] parses everything for the
     * drawer, which is the only thing that genuinely needs every title.
     */
    class Stored internal constructor(
        private val encoded: String,
        private val sourceChars: Int,
        private val rowsStart: Int,
        private val rowsEnd: Int,
    ) {

        /** How many rows there are, counted without reading one. */
        val lineCount: Int = countRows()

        /**
         * The row addressed by its position, or null when it is not a row this codec wrote.
         *
         * The position is the row's name as well as its address: a file whose rows disagree with
         * where they sit is not one this codec produced, and treating it as one would resolve a
         * saved reading position against the wrong chapter.
         */
        fun chapter(line: Int): Chapter? {
            if (line !in 0 until lineCount) return null
            var cursor = rowsStart
            repeat(line) {
                val end = rowEnd(cursor)
                if (end >= rowsEnd) return null
                cursor = end + 1
            }
            return parse(cursor, rowEnd(cursor), line)
        }

        /** Every row in order, or null on the first one that is not a row. */
        fun rows(): List<Chapter>? {
            val chapters = ArrayList<Chapter>(lineCount)
            var previousStart = -1
            var cursor = rowsStart
            var line = 0
            while (cursor < rowsEnd) {
                val end = rowEnd(cursor)
                val chapter = parse(cursor, end, line) ?: return null
                // Offsets that do not ascend are not a table of contents: the reader's saved
                // position is resolved against them, and a stale cache must be discarded, not
                // sorted into shape.
                if (chapter.charStart <= previousStart) return null
                previousStart = chapter.charStart
                chapters += chapter
                cursor = end + 1
                line += 1
            }
            return chapters
        }

        private fun countRows(): Int {
            if (rowsEnd <= rowsStart) return 0
            var rows = 1
            var index = rowsStart
            while (index < rowsEnd) {
                if (encoded[index] == '\n') rows += 1
                index += 1
            }
            return rows
        }

        /** The end of the row starting at [from]: its newline, or the end of the last row. */
        private fun rowEnd(from: Int): Int =
            encoded.indexOf('\n', from).let { if (it in from until rowsEnd) it else rowsEnd }

        private fun parse(
            start: Int,
            end: Int,
            line: Int,
        ): Chapter? {
            val first = tabAt(start, end) ?: return null
            val index = int(start, first) ?: return null
            val second = tabAt(first + 1, end) ?: return null
            val level = int(first + 1, second) ?: return null
            val third = tabAt(second + 1, end) ?: return null
            val charStart = int(second + 1, third) ?: return null
            val fourth = tabAt(third + 1, end) ?: return null
            val charEnd = int(third + 1, fourth) ?: return null
            // Everything past the fourth separator is the title, so a fifth tab can only be one
            // that `encode` would have escaped.
            val title = unescape(fourth + 1, end) ?: return null

            if (index != line) return null
            if (level < 0 || charStart < 0 || charEnd < charStart || charEnd > sourceChars) return null
            return Chapter(index, title, level, charStart = charStart, charEnd = charEnd)
        }

        private fun tabAt(
            from: Int,
            end: Int,
        ): Int? {
            var index = from
            while (index < end) {
                if (encoded[index] == '\t') return index
                index += 1
            }
            return null
        }

        /**
         * A field's number, read in place.
         *
         * `substring().toIntOrNull()` would allocate two strings and a list per row, which across
         * 9 500 rows is the difference between a parse and a garbage collection. Overflow is refused
         * rather than wrapped: a wrapped offset is still a number, and it would then be compared
         * against the document length as though someone had meant it.
         */
        private fun int(
            from: Int,
            to: Int,
        ): Int? {
            if (from >= to) return null
            var index = from
            val negative = encoded[index] == '-'
            if (negative) index += 1
            if (index >= to) return null
            var value = 0
            while (index < to) {
                val digit = encoded[index] - '0'
                if (digit !in 0..9) return null
                if (value > (Int.MAX_VALUE - digit) / 10) return null
                value = value * 10 + digit
                index += 1
            }
            return if (negative) -value else value
        }

        /** The title field, unescaped; null on a raw separator or an escape [encode] would not write. */
        private fun unescape(
            from: Int,
            to: Int,
        ): String? = buildString(to - from) {
            var index = from
            while (index < to) {
                val char = encoded[index]
                when (char) {
                    '\t' -> return null
                    '\\' -> {
                        index += 1
                        if (index >= to) return null
                        when (encoded[index]) {
                            '\\' -> append('\\')
                            't' -> append('\t')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            else -> return null
                        }
                    }

                    else -> append(char)
                }
                index += 1
            }
        }
    }

    private fun escape(title: String): String = buildString {
        for (char in title) {
            when (char) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }
}
