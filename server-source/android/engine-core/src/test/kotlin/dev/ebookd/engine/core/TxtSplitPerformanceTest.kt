package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The M8 acceptance number: splitting a 10 MB novel must stay inside the interaction budget.
 *
 * This is a *latency* test, not a throughput one: the split runs when a book is opened for the
 * first time, and a reader who waits noticeably at that moment assumes the app is broken. The
 * measurement is best-of-five after a warm-up because a JIT that has never seen the regexes
 * measures the compiler, not the algorithm.
 *
 * It still measures whatever else is running. The same binary scored 52.9 ms while an emulator was
 * booting on this machine and 28.1 ms idle, so a failure here is worth re-running with the box
 * quiet before the splitter is blamed for it.
 */
class TxtSplitPerformanceTest {

    /**
     * Sized in *bytes*, which is how a user's file is measured: a 10 MB Chinese text is about
     * 3.5 million characters, not ten million. Building the string to ten million characters would
     * be measuring a 30 MB file and calling it 10 MB.
     */
    private fun novel(): String {
        val paragraph = "夜色从海面漫上来，灯塔的光一圈一圈扫过水面，风把碎雪吹得斜斜的。渡口空无一人。\n\n"
        val firstChapter = buildString {
            appendLine("第1章 测试章节")
            appendLine()
            repeat(20) { append(paragraph) }
        }
        val bytesPerChapter = firstChapter.toByteArray(Charsets.UTF_8).size
        val chapters = (10 * 1024 * 1024 + bytesPerChapter - 1) / bytesPerChapter
        return buildString {
            for (number in 1..chapters) {
                appendLine("第${number}章 测试章节")
                appendLine()
                repeat(20) { append(paragraph) }
            }
        }
    }

    @Test
    fun `splitting a 10 MB novel stays inside the interaction budget`() {
        val text = novel()
        assertThat(text.toByteArray(Charsets.UTF_8).size).isAtLeast(10 * 1024 * 1024)

        // Warm the JIT on a smaller slice first, and hand the collector its garbage before timing.
        // The suite shares one JVM, and without this the split gets charged for a GC pause caused
        // by another test's allocations — a number that measures the harness, not the algorithm.
        repeat(2) { TxtChapterSplitter.split(text.substring(0, 3_000_000)) }
        System.gc()

        var best = Long.MAX_VALUE
        var marks = 0
        repeat(5) {
            val started = System.nanoTime()
            marks = TxtChapterSplitter.split(text).marks.size
            best = minOf(best, System.nanoTime() - started)
        }
        val millis = best / 1_000_000.0
        println("10 MB split: ${"%.1f".format(millis)} ms, $marks chapters")

        assertThat(marks).isAtLeast(1_000)
        assertThat(millis).isLessThan(50.0)
    }
}
