package dev.ebookd.engine.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.mozilla.universalchardet.UniversalDetector

/**
 * Charset detection for TXT files, kept semantically identical to the server's `detectCharset`.
 *
 * Both sides must agree on this because the same book is decoded once here and once there (preview
 * and word counts), and a disagreement is visible: the server shows a correct title from the first
 * line while the reader shows mojibake. The order of evidence is the same on both sides —
 *
 *  1. A BOM is a statement, not a guess. If one is present, it wins.
 *  2. A *strict* UTF-8 decode is a test, not a heuristic: if the bytes decode end to end without
 *     an error they really are UTF-8, and pure ASCII is trivially valid UTF-8, which is the right
 *     answer for it too. This removes the statistical guesswork for the common case.
 *  3. `juniversalchardet` for the genuinely ambiguous legacy files, *but only when its answer
 *     survives the same quality gate as everything else*. The library reports no confidence
 *     number in this version (only a charset once its internal threshold is met), so the decode
 *     it implies is scored and a disqualified result falls through instead of sticking.
 *  4. A trial decode over a fixed candidate set, scoring each result — this is the server's
 *     primary path and our last resort, so both sides converge on the same answer even without a
 *     detector.
 *
 * The answer is meant to be *persisted* and never re-derived on open: GB18030 and UTF-8 decode
 * each other's Chinese bytes into something that looks like text, so a wrong guess is silent, and
 * the only reliable fix is the user correcting it once.
 */
object CharsetDetector {

    /**
     * Candidates for the trial decode, in preference order. UTF-8 is tested strictly before this.
     *
     * UTF-16 belongs in the list because a BOM-less UTF-16 Chinese file is common from Chinese
     * authoring tools and the strict UTF-8 test cannot always catch it. Measured on this repo's
     * fixtures, the separation is not marginal: real GBK bytes decoded as UTF-16 come out at 0 %
     * Han characters, while real UTF-16 bytes decoded as GB18030 are 17 % control characters — so
     * [scoreText] picks the right one without a tie.
     */
    val CANDIDATES = listOf("GB18030", "Big5", "UTF-16LE", "UTF-16BE", "Shift_JIS", "EUC-KR", "windows-1252")

    /** Below this, a caller should treat the answer as a hint and offer the user a codepage switch. */
    const val REVIEW_CONFIDENCE = 0.6

    private const val SAMPLE_BYTES = 64 * 1024
    private const val SCORE_SAMPLE_CHARS = 4000

    data class Result(
        val charset: String,
        /** 0..1; see [REVIEW_CONFIDENCE]. */
        val confidence: Double,
        val text: String,
    )

    fun detect(bytes: ByteArray): Result {
        bomOf(bytes)?.let { bom ->
            val body = bytes.copyOfRange(bom.width, bytes.size)
            val text = decode(body, bom.charset, strict = false) ?: ""
            return Result(bom.charset, 0.99, text)
        }

        val truncated = bytes.size > SAMPLE_BYTES
        val sample = if (truncated) bytes.copyOf(SAMPLE_BYTES) else bytes

        // A strict decode is a proof: no guessing needed when it succeeds. Its *failure* is not a
        // proof on a truncated sample: a cut landing inside a multi-byte character throws on a file
        // that is valid UTF-8 end to end, and every candidate below then decodes cleanly — GB18030
        // turns any byte soup into plausible-looking garbage, which is how a whole book becomes
        // mojibake and every chapter heading stops matching. Back off the partial tail before
        // believing the failure.
        if (strictUtf8(sample, truncated) != null) {
            val nonAscii = sample.any { it.toInt() and 0xff > 0x7f }
            val text = decode(bytes, "UTF-8", strict = false) ?: ""
            // BOM-less UTF-16 Chinese is the one input that passes the strict UTF-8 test while
            // being nothing of the sort: every code unit's two bytes land in the ASCII range, so
            // the file reads as UTF-8 with a NUL between each character. A novel has no reason to
            // contain a pile of C0 controls, so that is the tell.
            if (!isControlSoup(text)) {
                return Result("UTF-8", if (nonAscii) 0.99 else 0.95, text)
            }
        }

        detectedByLibrary(sample)?.let { charset ->
            val decoded = decode(sample, charset, strict = false) ?: return@let
            val score = scoreText(decoded)
            if (score < 0) return@let
            val text = decode(bytes, charset, strict = false) ?: return@let
            return Result(charset, minOf(0.9, 0.5 + score), text)
        }

        var best: Triple<String, Double, String>? = null
        for (charset in CANDIDATES) {
            val decoded = decode(sample, charset, strict = false) ?: continue
            val score = scoreText(decoded)
            // A score of -1 means the decode contains replacement or control characters at a rate
            // that no real codepage mix produces; trying to rank it is how mojibake becomes sticky.
            if (score < 0) continue
            if (best == null || score > best.second) best = Triple(charset, score, decoded)
        }
        if (best != null) {
            val charset = best.first
            val text = decode(bytes, charset, strict = false) ?: ""
            return Result(charset, minOf(0.75, 0.4 + best.second), text)
        }
        // Everything above already proved the bytes are not strictly UTF-8, so answering "UTF-8"
        // here would decode a GBK novel into a page of replacement characters. GB18030 accepts any
        // byte pair: undecided files get visible Han instead of boxes.
        return Result("GB18030", 0.2, decode(bytes, "GB18030", strict = false) ?: "")
    }

    /**
     * A strict UTF-8 decode of [sample], retrying with up to three bytes dropped from the end when
     * the sample is a truncation — a UTF-8 sequence is at most four bytes, so three is the longest
     * partial tail a cut can leave behind.
     */
    private fun strictUtf8(sample: ByteArray, truncated: Boolean): String? {
        decode(sample, "UTF-8", strict = true)?.let { return it }
        if (!truncated) return null
        for (drop in 1..3) {
            val trimmed = sample.copyOfRange(0, sample.size - drop)
            decode(trimmed, "UTF-8", strict = true)?.let { return it }
        }
        return null
    }

    /** More than 3 % C0 controls is not prose in any codepage; a novel has no reason to contain them. */
    private fun isControlSoup(text: CharSequence): Boolean {
        if (text.isEmpty()) return false
        var controls = 0
        for (c in text) {
            if (c != '\n' && c != '\r' && c != '\t' && c.code in 0x00..0x1F) controls++
        }
        return controls * 100 > text.length * 3
    }

    /**
     * `juniversalchardet` is asked only about files that are not valid UTF-8 and carry no BOM —
     * precisely the case where its statistical model earns its keep. Its own "ASCII" and
     * "UTF-8" answers are ignored because the strict test above already handled those exactly.
     */
    private fun detectedByLibrary(sample: ByteArray): String? {
        val detector = UniversalDetector()
        try {
            detector.handleData(sample, 0, sample.size)
            detector.dataEnd()
            val charset = detector.detectedCharset ?: return null
            if (charset.equals("ASCII", ignoreCase = true) || charset.equals("UTF-8", ignoreCase = true)) return null
            val name = runCatching { Charset.forName(charset).name() }.getOrNull() ?: return null
            if (name.equals("UTF-8", ignoreCase = true) || name.equals("US-ASCII", ignoreCase = true)) return null
            return name
        } finally {
            detector.reset()
        }
    }

    private fun bomOf(bytes: ByteArray): Bom? {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Bom("UTF-8", 3)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Bom("UTF-16LE", 2)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Bom("UTF-16BE", 2)
        return null
    }

    private data class Bom(val charset: String, val width: Int)

    private fun decode(bytes: ByteArray, charsetName: String, strict: Boolean): String? {
        val charset = runCatching { Charset.forName(charsetName) }.getOrNull() ?: return null
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        return try {
            charset.newDecoder()
                .onMalformedInput(action)
                .onUnmappableCharacter(action)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /**
     * The same quality judgement the server makes: Chinese prose is almost all CJK plus ASCII
     * punctuation, while a wrong codepage turns it into box-drawing, Cyrillic or Hangul runs.
     * Returns a 0..1 ratio, or -1 when the decode is disqualified outright.
     */
    private fun scoreText(text: String): Double {
        val sample = text.take(SCORE_SAMPLE_CHARS)
        if (sample.isEmpty()) return 0.0
        var cjk = 0
        var latin = 0
        var replacement = 0
        var control = 0
        for (char in sample) {
            val cp = char.code
            when {
                cp == 0xFFFD -> replacement += 1
                cp < 0x20 && cp != 0x09 && cp != 0x0A && cp != 0x0D -> control += 1
                cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF -> cjk += 1
                cp in 0x41..0x7A -> latin += 1
            }
        }
        val total = sample.length.toDouble()
        if (replacement / total > 0.01 || control / total > 0.01) return -1.0
        return (cjk + latin) / total
    }
}

/** Re-encodes text as UTF-8 — the normalizer's output is always stored in this form. */
fun String.toUtf8Bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)
