package dev.ebookd.core.storage

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The store's guarantees, tested where they actually live: on the filesystem.
 *
 * These are small assertions for a large reason. Content-addressed storage is what makes "the same
 * book in two formats costs one copy" and "the server replaced the bytes, so the old cache is
 * invalid" true without any bookkeeping, and both properties fail silently — a wrong path shape or
 * a rename that clobbers content is invisible until a reader's library has a hole in it.
 */
class ObjectStoreTest {

    private lateinit var root: File
    private lateinit var store: ObjectStore

    private val shaA = ObjectStore.sha256("第一本书的内容".toByteArray())
    private val shaB = ObjectStore.sha256("第二本书的内容".toByteArray())

    @Before
    fun setUp() {
        root = createTempDirectory("ebookd-store-").toFile()
        store = ObjectStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `an object is addressed by its hash under a two-character shard`() {
        val path = store.pathFor(shaA)
        assertEquals("${shaA.take(2)}/${shaA}.bin", path.relativeTo(root).path.replace('\\', '/'))
        assertFalse("a fresh address should not claim to exist", store.contains(shaA))
    }

    @Test
    fun `the same bytes from two files occupy the disk once`() {
        val first = store.tempFor("file-1")
        first.parentFile?.mkdirs()
        first.writeBytes("同一份内容".toByteArray())
        val promoted = store.promote(first, ObjectStore.sha256("同一份内容".toByteArray()))

        val second = store.tempFor("file-2")
        second.writeBytes("同一份内容".toByteArray())
        val again = store.promote(second, ObjectStore.sha256("同一份内容".toByteArray()))

        assertEquals(promoted, again)
        assertFalse("the temp file of a duplicate upload survived", second.exists())
        assertEquals(promoted.length(), store.usedBytes())
    }

    @Test
    fun `promoting never overwrites an object that already exists`() {
        store.pathFor(shaA).apply { parentFile?.mkdirs() }.writeBytes("original".toByteArray())
        val before = store.pathFor(shaA).length()

        val temp = store.tempFor("file-3").apply { writeBytes("different".toByteArray()) }
        store.promote(temp, shaA)

        assertEquals("an existing object was clobbered", before, store.pathFor(shaA).length())
        assertEquals("original", store.pathFor(shaA).readText())
    }

    @Test
    fun `a temp file keeps the exact number of bytes written, which is the resume cursor`() {
        val temp = store.tempFor("file-4")
        temp.writeBytes(ByteArray(4096))
        assertEquals(4096L, temp.length())
        // A second attempt resumes from what is on disk; nothing else can be trusted after a crash.
        assertEquals(temp.length(), store.tempFor("file-4").length())
    }

    @Test
    fun `deleting one object leaves the rest intact`() {
        store.pathFor(shaA).apply { parentFile?.mkdirs() }.writeBytes("a".toByteArray())
        store.pathFor(shaB).apply { parentFile?.mkdirs() }.writeBytes("b".toByteArray())

        assertTrue(store.delete(shaA))
        assertFalse(store.contains(shaA))
        assertTrue(store.contains(shaB))
    }

    @Test
    fun `two different contents get two different addresses`() {
        assertNotEquals(shaA, shaB)
        assertEquals(64, shaA.length)
        assertTrue(shaA.all { it in "0123456789abcdef" })
    }
}
