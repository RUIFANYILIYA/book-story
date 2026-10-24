package dev.ebookd.core.download

import dev.ebookd.core.storage.ObjectStore
import java.io.File
import kotlin.io.path.createTempDirectory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okio.Buffer
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two paths where a downloader silently corrupts a library, tested against a scripted server.
 *
 * These are unit tests rather than live-server tests because they have to be *deterministic*: the
 * server has to change its mind about a file mid-download, which is exactly the case a real server
 * will not reproduce on demand. The live contract test covers the ordinary ranged path.
 */
class RangeDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private lateinit var objects: ObjectStore
    private lateinit var client: OkHttpClient

    private val body = "第一章 起风\n\n".repeat(400).toByteArray() // ~6 KB of UTF-8
    private val sha = ObjectStore.sha256(body)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        root = createTempDirectory("ebookd-objects-").toFile()
        objects = ObjectStore(root)
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    private fun supplier(path: String = "/files/1/download"): (Long?, String?) -> Request = { start, ifRange ->
        Request.Builder()
            .url(server.url(path))
            .apply {
                if (start != null) {
                    header("Range", "bytes=$start-")
                    ifRange?.let { header("If-Range", it) }
                }
            }
            .build()
    }

    @Test
    fun `a 200 answer to a ranged request restarts the file instead of appending to it`() {
        // The server's `If-Range` mismatch path: 200 with the whole file. Appending that body to the
        // bytes already on disk is how a half-old, half-new book gets assembled - it opens cleanly
        // and is wrong, which is the worst possible failure for a reader to discover.
        val partial = body.copyOfRange(0, 1000)
        objects.tempFor("file-1").apply { parentFile?.mkdirs() }.writeBytes(partial)

        server.enqueue(headResponse(etag = "\"new\""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(body.toByteString())))

        val result = RangeDownloader(client, objects, segmentBytes = 2048)
            .downloadSync("file-1")

        assertEquals(RangeDownloader.Status.COMPLETED, result.status)
        assertTrue("resumedFrom should record the bytes we threw away", result.resumedFrom > 0)
        assertEquals(sha, result.sha256)
        assertEquals(body.size.toLong(), result.file!!.length())
    }

    @Test
    fun `a response that transfers nothing fails instead of spinning`() {
        server.enqueue(headResponse(etag = "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-${body.size - 1}/${body.size}"))

        val result = RangeDownloader(client, objects, segmentBytes = 2048).downloadSync("file-2")

        assertEquals(RangeDownloader.Status.FAILED, result.status)
        assertTrue(result.message.orEmpty().contains("没有返回新的字节"))
    }

    @Test
    fun `a hash mismatch is deleted rather than kept as a working file`() {
        server.enqueue(headResponse(etag = "\"v1\""))
        server.enqueue(fullResponse())

        // A wrong expectation is what a replaced file or a corrupt transfer looks like from the
        // client's seat; either way the bytes must not be promoted into the library.
        val result = RangeDownloader(client, objects, segmentBytes = body.size + 1)
            .downloadSync("file-3", expectedSha256 = "0".repeat(64))

        assertEquals(RangeDownloader.Status.INTEGRITY_FAILURE, result.status)
        assertFalse("the temp file survived an integrity failure", objects.tempFor("file-3").exists())
        assertFalse("a mismatching object was promoted", objects.pathFor("0".repeat(64)).exists())
    }

    @Test
    fun `a second request for the same content does not touch the network again`() {
        objects.pathFor(sha).apply { parentFile?.mkdirs() }.writeBytes(body)

        val result = RangeDownloader(client, objects).downloadSync("file-4", expectedSha256 = sha)

        assertEquals(RangeDownloader.Status.ALREADY_PRESENT, result.status)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an interrupted segment leaves a temp file that the next attempt resumes from`() {
        server.enqueue(headResponse(etag = "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(body.take(2048).toByteArray().toByteString())))

        val downloader = RangeDownloader(client, objects, segmentBytes = 2048)
        val interrupted = downloader.downloadSync("file-5", stopAfterSegments = 1)
        assertEquals(RangeDownloader.Status.FAILED, interrupted.status)
        val partial = objects.tempFor("file-5").length()
        assertTrue("nothing was left behind to resume from", partial > 0)

        server.enqueue(headResponse(etag = "\"v1\""))
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(body.copyOfRange(2048, body.size).toByteString())))

        val resumed = downloader.downloadSync("file-5")
        assertEquals(RangeDownloader.Status.COMPLETED, resumed.status)
        assertEquals(partial, resumed.resumedFrom)
        assertEquals(sha, resumed.sha256)

        val sent = (0 until server.requestCount).map { server.takeRequest() }
        assertTrue("the resumed request did not ask for the missing bytes", sent.any { it.getHeader("Range") == "bytes=$partial-" } &&
            sent.any { it.getHeader("Range") == "bytes=0-" })
        assertTrue("the resumed request omitted If-Range", sent.any { it.getHeader("If-Range") != null })
    }

    private fun headResponse(etag: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", body.size)
        .setHeader("Accept-Ranges", "bytes")
        .setHeader("ETag", etag)
        .setHeader("X-Content-Sha256", sha)

    private fun fullResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Length", body.size)
        .setHeader("ETag", "\"v1\"")
        .setHeader("X-Content-Sha256", sha)
        .setBody(Buffer().write(body.toByteString()))

    /** The suspend entry point, driven from a plain test thread. */
    private fun RangeDownloader.downloadSync(
        fileId: String,
        expectedSha256: String? = null,
        stopAfterSegments: Int = Int.MAX_VALUE,
    ): RangeDownloader.Result = kotlinx.coroutines.runBlocking {
        download(fileId, expectedSha256, supplier(), { _, _ -> }, stopAfterSegments)
    }
}
