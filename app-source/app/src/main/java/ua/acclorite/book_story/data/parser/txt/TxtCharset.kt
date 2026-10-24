/*
 * Book's Story — free and open-source Material You eBook reader.
 * Copyright (C) 2024-2025 Acclorite
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ua.acclorite.book_story.data.parser.txt

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Which encoding a TXT actually uses.
 *
 * Reading a GBK novel as UTF-8 does not fail — it produces text that parses cleanly and reads like
 * noise, so the damage only shows up on the reader screen. The ladder below goes from certain to
 * guessed: a BOM is the writer's own statement, a clean strict UTF-8 decode rules UTF-8 in, and
 * only after that do the single-byte-family candidates get compared.
 *
 * Android's public ICU does not expose a charset detector, and pulling in a full one to answer a
 * question with three common answers is not worth 10 MB of the APK.
 */
object TxtCharset {

    private const val SNIFF_BYTES = 64 * 1024

    private val GB18030: Charset = Charset.forName("GB18030")

    data class Detected(val charset: Charset, val bomBytes: Int)

    fun detect(input: InputStream): Detected {
        val head = input.sniff(SNIFF_BYTES)

        bomOf(head)?.let { return it }

        if (decodesAsUtf8(head)) return Detected(StandardCharsets.UTF_8, 0)

        val best = CANDIDATES
            .mapNotNull { charset -> decodeLeniently(head, charset)?.let { score(it) to charset } }
            .maxByOrNull { (value, _) -> value }
            ?.takeIf { (value, _) -> value > 0.5f }
            ?.second

        // Not UTF-8 — the strict test above said so — so falling back to UTF-8 here is the one
        // answer guaranteed to be wrong: it turns every Han byte pair into a replacement character
        // and the reader shows a page of boxes. GB18030 accepts any byte sequence, so the worst it
        // can do is show wrong-but-visible characters.
        return Detected(best ?: GB18030, 0)
    }

    /**
     * GB18030 is listed before Big5 deliberately: GB18030 accepts almost any pair of high bytes, so
     * it will happily decode a Big5 file into the wrong Han characters. When a file really is Big5
     * this order picks wrong, and nothing here can tell the two apart without a simplified versus
     * traditional character model.
     */
    private val CANDIDATES = listOf(
        "GB18030", "Big5", "UTF-16LE", "UTF-16BE", "Shift_JIS", "EUC-KR", "windows-1252"
    ).mapNotNull { name -> runCatching { Charset.forName(name) }.getOrNull() }

    /**
     * UTF-16 is returned as the plain `UTF-16` charset when a BOM is present: its decoder reads the
     * BOM and derives the byte order from it, while naming LE or BE leaves the BOM behind as a
     * U+FEFF that then shows up glued to the first chapter title.
     */
    private fun bomOf(head: ByteArray): Detected? {
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
            // Java's UTF-8 decoder does not consume a UTF-8 BOM, so it has to be skipped by hand.
            return Detected(StandardCharsets.UTF_8, 3)
        }
        if (head.size >= 2) {
            val le = head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()
            val be = head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()
            if (le || be) return Detected(StandardCharsets.UTF_16, 0)
        }
        return null
    }

    private fun decodesAsUtf8(head: ByteArray): Boolean {
        if (head.isEmpty()) return true

        val whole = decodeWith(head, StandardCharsets.UTF_8)
        if (whole != null && !isControlSoup(whole)) return true

        // The sniff window can end inside a multi-byte character, which is not evidence that the
        // file is not UTF-8.
        val trimmed = decodeWith(
            head.copyOf(head.size - minOf(3, head.size)), StandardCharsets.UTF_8
        )
        return trimmed != null && !isControlSoup(trimmed)
    }

    /**
     * BOM-less UTF-16 Chinese text decodes as "valid UTF-8" almost every time, because each code
     * unit's two bytes stay inside the ASCII range. What gives it away is the pile of C0 control
     * characters nobody writes in a novel.
     */
    private fun isControlSoup(text: CharSequence): Boolean {
        if (text.isEmpty()) return false
        var controls = 0
        for (c in text) {
            if (c != '\n' && c != '\r' && c != '\t' && c.code in 0x00..0x1F) controls++
        }
        return controls * 100 > text.length * 3
    }

    private fun decodeWith(head: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(head))
            .toString()
    }.getOrNull()

    /**
     * Scoring needs a string from every candidate, and a real GBK novel contains the odd byte pair
     * its encoder never emits — one such pair under `REPORT` disqualifies GB18030 for the whole
     * file, which is how three of eight books in a real library lost their encoding. Replacement
     * characters are still visible to [score], so a genuinely wrong codepage still loses.
     */
    private fun decodeLeniently(head: ByteArray, charset: Charset): String? = runCatching {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(head))
            .toString()
    }.getOrNull()

    /** Share of characters that look like text a reader was meant to see. */
    private fun score(text: String): Float {
        if (text.isEmpty()) return 0f
        var good = 0
        for (c in text) {
            val cp = c.code
            val wanted = when {
                // A replacement character is never something a reader was meant to see. It sits
                // inside the full-width range below, so it has to be excluded before that test.
                cp == 0xFFFD -> false
                cp in 0x4E00..0x9FFF -> true       // CJK ideographs
                cp in 0x3400..0x4DBF -> true       // extension A
                cp in 0x3000..0x303F -> true       // CJK punctuation
                cp in 0xFF00..0xFFEF -> true       // full width forms
                cp == 0x000A || cp == 0x000D || cp == 0x0009 -> true
                cp in 0x0020..0x007E -> true       // printable ASCII
                // Deliberately *not* the Latin-1 range: a GBK novel read as windows-1252 turns every
                // Han byte pair into an accented Latin letter, so counting those as good let the
                // wrong codepage win a whole library. Western text keeps the same treatment it had
                // before, which is imperfect but never steals a Chinese book.
                else -> false
            }
            if (wanted) good++
        }
        return good.toFloat() / text.length
    }

    private fun InputStream.sniff(limit: Int): ByteArray {
        val out = ByteArrayOutputStream(limit)
        val buffer = ByteArray(8 * 1024)
        var read = 0
        while (read < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - read))
            if (n < 0) break
            out.write(buffer, 0, n)
            read += n
        }
        return out.toByteArray()
    }
}
