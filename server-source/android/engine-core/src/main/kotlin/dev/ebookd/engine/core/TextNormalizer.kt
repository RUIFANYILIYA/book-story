package dev.ebookd.engine.core

/**
 * Turns decoded bytes into the canonical text every later stage works on: the splitter's offsets,
 * the reader's anchors and the cached `<sha>.utf8.txt` are all expressed in *this* text, so the
 * transformation has to be deterministic and, above all, idempotent — running it twice must not
 * move a single character offset.
 *
 * The single highest-value fix in here is paragraph reflow. Plain-text books are usually hard-
 * wrapped at some column count, which a reader would otherwise show as a ragged paragraph per
 * conversion line; joining those lines back together is the difference between "a converted file"
 * and "a book". The guard rails around it exist because the same transform can destroy a text if
 * it merges things that were never one paragraph.
 */
object TextNormalizer {

    /**
     * The indent marker: one U+3000 at the start of a line means "this line starts a paragraph".
     *
     * A character is used rather than a flag because the normalized text is what gets persisted and
     * what offsets are computed against — a marker survives being written to disk and read back,
     * while a "the third line was indented" table would not. Runs of indentation are collapsed to
     * exactly one marker, which is what makes the transform idempotent; the reader strips it and
     * applies its own `firstLineIndentEm`, so files that indent with four spaces and files that
     * indent with full-width spaces end up rendering identically.
     */
    const val INDENT = '\u3000'

    /**
     * What may end a sentence for reflow purposes. `。！？` are the Chinese terminators and
     * `…”』）` the closers that follow them; the ASCII ones matter for translated and English
     * texts. `、` is deliberately absent: it separates list items inside a sentence, so a line
     * ending in it is a hard-wrapped line, not a finished paragraph.
     */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '…', '”', '』', '）', '.', '!', '?', ')', '"', '\'')

    /** A bare page number on its own line. */
    private val BARE_NUMBER = Regex("^\\s*(?:\\d{1,5}|[IVXLCDM]{1,7})\\s*$")

    /** `第 12 页`, with or without the spaces. */
    private val PAGE_LABEL = Regex("^\\s*第\\s*\\d+\\s*页\\s*$")

    /**
     * Furniture thresholds. A line is only removed when its exact text appears at least this many
     * times, is short, and is *spread across the document* rather than clustered in one place.
     * The spread requirement is the safety valve: a refrain, a chorus or a repeated line of verse
     * can easily occur eight times, but it occurs inside one chapter, while a running header or a
     * footer bar occurs once per page from beginning to end.
     */
    private const val FURNITURE_MIN_COUNT = 8
    private const val FURNITURE_MAX_CHARS = 40
    private const val FURNITURE_GRID = 10
    private const val FURNITURE_MIN_BUCKETS = 4

    data class Result(val text: String, val warnings: List<String> = emptyList())

    fun normalize(raw: String): Result {
        val warnings = mutableListOf<String>()
        val unified = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            // A stray BOM mid-file is zero-width and invisible in every editor; it only ever causes
            // a phantom character in the first paragraph, so it is removed wherever it appears.
            .replace("\uFEFF", "")
            .replace("\t", "    ")

        var lines = unified.split('\n').map { it.trimEnd() }
        lines = stripFurniture(lines, warnings)
        lines = lines.map(::canonicalIndent)
        lines = collapseBlankRuns(lines)
        lines = reflow(lines)
        return Result(lines.joinToString("\n").trim('\n'), warnings)
    }

    fun normalizeText(raw: String): String = normalize(raw).text

    // ------------------------------------------------------------------ passes

    /**
     * Removes running headers, footers and page numbers.
     *
     * The obvious rule — "a bare number on its own line is a page number" — is wrong on its own
     * because a lone `12` in a list of numbers is content. What makes a line furniture is being
     * *systematic*: the same shape once per page, from the front of the file to the back.
     *
     * Counting is by *shape*, not by exact text, and that distinction is not academic: page numbers
     * increment, so `12`, `13` and `14` are three different strings that are one repeating shape.
     * Exact-text counting strips running headers and finds no page number at all.
     */
    private fun stripFurniture(lines: List<String>, warnings: MutableList<String>): List<String> {
        if (lines.size < FURNITURE_MIN_COUNT * 2) return lines

        data class Appearance(var count: Int = 0, val buckets: MutableSet<Int> = mutableSetOf())

        val appearances = mutableMapOf<String, Appearance>()
        var example = ""
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.length > FURNITURE_MAX_CHARS) continue
            val key = furnitureKey(trimmed) ?: continue
            val entry = appearances.getOrPut(key) { Appearance() }
            entry.count += 1
            entry.buckets += index * FURNITURE_GRID / lines.size
            if (key == "number" || key == "page") example = trimmed
        }

        val furniture = appearances.filter { (_, entry) ->
            entry.count >= FURNITURE_MIN_COUNT && entry.buckets.size >= FURNITURE_MIN_BUCKETS
        }.keys

        if (furniture.isEmpty()) return lines
        val removed = lines.count { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && furnitureKey(trimmed) in furniture
        }
        warnings += if (example.isEmpty()) {
            "剥离了 $removed 行页眉页脚"
        } else {
            "剥离了 $removed 行页眉页脚/页码（如 $example）"
        }
        return lines.filterNot { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && furnitureKey(trimmed) in furniture
        }
    }

    /**
     * The shape of a line, or null when it cannot be furniture: page numbers and `第N页` are their
     * own shapes so that an incrementing sequence still counts as one repeating line, everything
     * else repeats by exact text. A line with a sentence ending is never furniture in any shape —
     * a header carries a title, not a finished sentence.
     */
    private fun furnitureKey(trimmed: String): String? = when {
        BARE_NUMBER.matches(trimmed) -> "number"
        PAGE_LABEL.matches(trimmed) -> "page"
        trimmed.length <= 20 && trimmed.last() !in SENTENCE_END -> "text:$trimmed"
        else -> null
    }

    /**
     * Collapses indentation to the single marker described in [INDENT].
     *
     * One leading ASCII space is treated as noise rather than indentation: converters and pasted
     * text produce it constantly, and honouring it would mark a hard-wrapped continuation line as
     * a paragraph start, which then stops the reflow pass from joining it to its paragraph.
     */
    private fun canonicalIndent(line: String): String {
        if (line.isBlank()) return ""
        var start = 0
        while (start < line.length && line[start] == ' ') start += 1
        if (start < line.length && line[start] == INDENT) {
            while (start < line.length && (line[start] == INDENT || line[start] == ' ')) start += 1
            return INDENT + line.substring(start)
        }
        return if (start >= 2) INDENT + line.substring(start) else line.substring(start)
    }

    /**
     * Collapses *excessive* blank runs: three or more blank lines become one. Two are left alone
     * because a pair of blank lines is a scene separator that authors and converters use
     * deliberately, and folding it into a paragraph break erases a distinction the text made.
     */
    private fun collapseBlankRuns(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var run = 0
        fun flush() {
            repeat(if (run >= 3) 1 else run) { out += "" }
            run = 0
        }
        for (line in lines) {
            if (line.isEmpty()) run += 1 else {
                flush()
                out += line
            }
        }
        flush()
        while (out.isNotEmpty() && out.first().isEmpty()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out
    }

    /**
     * Joins hard-wrapped lines back into paragraphs.
     *
     * A line is merged into its predecessor when the predecessor did not finish a sentence, and the
     * line itself does not start a new paragraph (no indent marker, not a heading, not blank).
     * The heading check is what keeps `第一章 …` from being swallowed by the last line of the
     * previous chapter when that line happens to end without punctuation.
     */
    private fun reflow(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) {
            val previous = out.lastOrNull()
            val mergeable = previous != null && previous.isNotEmpty() && line.isNotEmpty() &&
                !line.startsWith(INDENT) &&
                TxtChapterSplitter.headingLevel(stripIndent(line)) == null &&
                previous.last() !in SENTENCE_END
            if (mergeable) {
                out[out.size - 1] = previous + joiner(previous.last(), line.first()) + line
            } else {
                out += line
            }
        }
        return out
    }

    private fun stripIndent(line: String): String = line.removePrefix(INDENT.toString())

    /**
     * Latin text was wrapped between words, so the words need the space back when rejoined; CJK
     * text has no spaces to restore, and inserting one would put a visible gap inside a sentence.
     */
    private fun joiner(left: Char, right: Char): String {
        val latin = { c: Char -> c.code < 0x80 && c.isLetterOrDigit() }
        return if (latin(left) && latin(right)) " " else ""
    }
}
