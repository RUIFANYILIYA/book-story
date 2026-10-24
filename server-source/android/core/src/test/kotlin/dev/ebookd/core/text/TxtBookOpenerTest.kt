package dev.ebookd.core.text

import dev.ebookd.core.storage.ObjectStore
import dev.ebookd.engine.core.Block
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The second open must be a read, not a re-analysis.
 *
 * [TxtBookOpener] exists so a reader killed mid-book comes back instantly, and the property it has
 * to keep is stronger than "fast": the *same* chapter offsets both times. Re-running the splitter
 * on reopen would look identical and be a different claim — a heuristic change between releases
 * would move the offsets under a saved percentage and send the reader to another paragraph that is
 * still plausibly inside the right chapter, which is close enough that nobody files a bug. So the
 * offsets are compared across opens, and every corrupted-cache case below has to end in a rebuild
 * rather than in a partial answer.
 */
class TxtBookOpenerTest {

    private lateinit var root: File
    private lateinit var opener: TxtBookOpener

    private val line = "夜色从海面漫上来，灯塔的光一圈一圈扫过水面，风把碎雪吹得斜斜的。渡口空无一人，只有缆绳拍打船身的声音。"
    private val text = "第一章 起风\n\n${line.repeat(3)}\n\n第二章 落雨\n\n${line.repeat(3)}\n\n第三章 转晴\n\n${line.repeat(3)}\n"

    @Before
    fun setUp() {
        root = createTempDirectory("ebookd-opener-").toFile()
        opener = TxtBookOpener(ObjectStore(root))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun store(bytes: ByteArray): String {
        val sha = ObjectStore.sha256(bytes)
        val target = ObjectStore(root).pathFor(sha)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return sha
    }

    private fun cacheDir() = File(root, "txt/v${TxtBookOpener.PIPELINE_VERSION}")

    private fun anchors(opened: TxtBookOpener.OpenedTxtBook) =
        opened.outline().map { it.title to it.charStart }

    @Test
    fun `the first open analyzes and the second reads the same anchors back`() {
        val sha = store(text.toByteArray())

        val first = opener.open("测试书", sha)
        assertEquals(3, first.chapterCount)
        assertFalse("first open must not claim to be cached", first.fromCache)

        val second = opener.open("测试书", sha)
        assertTrue("reopening must hit the cache", second.fromCache)
        assertEquals(anchors(first), anchors(second))
        assertTrue(second.tookMillis <= first.tookMillis)
    }

    @Test
    fun `only the normalized text and the chapter list are cached`() {
        val sha = store(text.toByteArray())
        opener.open("测试书", sha)

        val names = cacheDir().walkTopDown().filter { it.isFile }.map { it.name }.toList().sorted()
        assertEquals(listOf("$sha.toc.txt", "$sha.u16.txt"), names)
    }

    @Test
    fun `the cached text is seekable by character offset`() {
        val sha = store(text.toByteArray())
        val opened = opener.open("测试书", sha)
        val file = File(cacheDir(), "$sha.u16.txt")

        // Two bytes per character and no byte-order mark: `chapterAt` seeks with charStart * 2, and
        // a BOM would shift every chapter by one character in a way that still mostly reads right.
        assertEquals(opened.normalizedChars * 2L, file.length())
        val head = file.readBytes().take(2)
        assertFalse("UTF-16LE must be written without a byte-order mark", head == listOf(0xFF.toByte(), 0xFE.toByte()))

        val chapter = opened.chapterAt(1)
        assertEquals(opened.outline()[1].charEnd - opened.outline()[1].charStart, chapter.charCount)
        assertTrue(chapter.title.startsWith("第二章"))
        assertTrue(chapter.blocks.any { it is Block.Paragraph })
        // Addressed directly, so the last chapter costs what the first one does: the open reads no
        // row it was not asked for, and a reader who is on chapter 3 000 of 9 500 never pays for the
        // 9 499 that are not on screen.
        val last = opened.chapterAt(opened.chapterCount - 1)
        assertEquals(opened.outline().last().charEnd - opened.outline().last().charStart, last.charCount)
        assertTrue(last.title.startsWith("第三章"))
    }

    @Test
    fun `a row that is not a row is rebuilt where it is read, not where the book is opened`() {
        val sha = store(text.toByteArray())
        opener.open("测试书", sha)
        val tocFile = File(cacheDir(), "$sha.toc.txt")
        // Exactly one row is damaged — the second chapter — and it is damaged in the title, which is
        // the field the inspector does not read.
        tocFile.writeText(tocFile.readText().replace("\t第二章 落雨\n", "\t第二章 落雨\t\n"))

        val reopened = opener.open("测试书", sha)
        assertTrue("inspecting a file is not the same as reading its rows", reopened.fromCache)
        assertEquals(3, reopened.chapterCount)
        assertTrue(reopened.chapterAt(0).title.startsWith("第一章"))

        // The row that will not parse is answered by rebuilding the cache from the source, which is
        // what an eager read would have done at open time — and it leaves the file repaired.
        assertTrue(reopened.chapterAt(1).title.startsWith("第二章"))
        assertFalse(tocFile.readText().contains("第二章 落雨\t\n"))
    }

    @Test
    fun `a non-UTF-8 file normalizes to the same anchors as the UTF-8 original`() {
        // Not asserting the detected label — that is CharsetDetector's test — but the property that
        // actually matters here: whichever route the bytes took, the chapter offsets must be the
        // ones a UTF-8 copy would have produced, or a re-encoded download moves the reader's place.
        val utf8Sha = store(text.toByteArray())
        val gbSha = store(String(text.toByteArray(charset("GB18030")), charset("GB18030")).toByteArray(charset("GB18030")))

        val utf8 = opener.open("测试书", utf8Sha)
        val gb = opener.open("测试书", gbSha)
        assertEquals(anchors(utf8), anchors(gb))
        assertEquals(utf8.normalizedChars, gb.normalizedChars)
    }

    @Test
    fun `a toc truncated at a line boundary is rebuilt, not served missing its last chapter`() {
        val sha = store(text.toByteArray())
        val analyzed = opener.open("测试书", sha)

        val tocFile = File(cacheDir(), "$sha.toc.txt")
        val lines = tocFile.readLines()
        tocFile.writeText(lines.dropLast(2).joinToString("\n") + "\n")

        val rebuilt = opener.open("测试书", sha)
        assertEquals(analyzed.chapterCount, rebuilt.chapterCount)
        assertFalse("a truncated cache must not be reported as a hit", rebuilt.fromCache)
    }

    @Test
    fun `a cache whose text half is missing is rebuilt rather than half-trusted`() {
        val sha = store(text.toByteArray())
        val first = opener.open("测试书", sha)
        File(cacheDir(), "$sha.u16.txt").delete()

        val second = opener.open("测试书", sha)
        assertFalse(second.fromCache)
        assertEquals(anchors(first), anchors(second))
    }

    @Test
    fun `replacing the bytes gets its own cache entry instead of the old chapters`() {
        val sha = store(text.toByteArray())
        val other = store((text + "\n第四章 终局\n\n" + line.repeat(3)).toByteArray())

        val first = opener.open("测试书", sha)
        val second = opener.open("测试书", other)
        assertFalse(second.fromCache)
        assertEquals(4, second.chapterCount)
        assertEquals(3, first.chapterCount)
    }

    @Test
    fun `opening a hash that was never stored fails loudly`() {
        val failure = runCatching { opener.open("幽灵", ObjectStore.sha256("never stored".toByteArray())) }
            .exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("no stored object"))
    }
}
