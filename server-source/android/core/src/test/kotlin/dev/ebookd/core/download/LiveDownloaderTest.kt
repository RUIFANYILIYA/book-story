package dev.ebookd.core.download

import dev.ebookd.core.net.DeviceDto
import dev.ebookd.core.net.EbookdApi
import dev.ebookd.core.net.LoginRequestDto
import dev.ebookd.core.net.MetaDto
import dev.ebookd.core.storage.ObjectStore
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The downloader against a running server, in *small* segments.
 *
 * The scripted test covers the paths a real server will not produce on demand; this one covers the
 * paths only a real server can answer for: whether `X-Content-Sha256` is really sent, whether the
 * ETag is the strong validator `If-Range` is compared against, and whether a `bytes=N-` request
 * returns exactly the suffix the client assumes. A mock that agrees with my own assumptions proves
 * nothing about those three.
 *
 * Skipped unless `ebookd.e2e.url` is provided, and skipped loudly rather than passed.
 */
class LiveDownloaderTest {

    private val baseUrl: String? = System.getProperty("ebookd.e2e.url")
    private val username: String = System.getProperty("ebookd.e2e.user") ?: "admin"
    private val password: String = System.getProperty("ebookd.e2e.password").orEmpty()
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a real book file downloads in 64 KiB segments and hashes to what the server promised`() {
        assumeTrue("no server configured", !baseUrl.isNullOrBlank())
        assumeTrue("no password configured", password.isNotEmpty())

        val root: File = createTempDirectory("ebookd-dl-").toFile()
        val objects = ObjectStore(root)
        // Counts every request the test's own client makes, so "satisfied from disk" can be
        // asserted as a fact about the network rather than as a status code the client gave itself.
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val plain = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                requests.incrementAndGet()
                chain.proceed(chain.request())
            }
            .build()

        val anonymous = retrofit(plain)
        assertEquals(MetaDto.PROTOCOL, runBlocking { anonymous.meta(1) }.protocol)
        val grant = runBlocking {
            anonymous.login(
                LoginRequestDto(username, password, DeviceDto(label = "e2e-download", platform = "android")),
            )
        }

        // One authenticated client for every call the test makes itself, so a forgotten header cannot
        // turn into a confusing 401 in the middle of a download assertion.
        val authed = plain.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${grant.accessToken}").build())
        }.build()
        val api = retrofit(authed)

        // Large enough to need several 64 KiB segments, and unique per run so the server's own
        // content-addressed store cannot hand back an earlier book.
        val nonce = System.nanoTime().toString()
        val content = buildString {
            appendLine("第一章 下载测试 $nonce")
            appendLine()
            while (length < 300_000) {
                appendLine("夜色从海面漫上来，灯塔的光一圈一圈扫过水面。样本编号 $nonce，用来确保每次运行都是一个新文件。")
            }
        }
        val upload = Request.Builder()
            .url("$baseUrl" + "api/v1/uploads")
            .post(
                MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "e2e-download-$nonce.txt", content.toRequestBody("text/plain".toMediaType()))
                    .build(),
            )
            .build()
        val uploaded = authed.newCall(upload).execute().use { it.code }
        assertTrue("upload failed with HTTP $uploaded", uploaded in 200..299)

        // The server titles a TXT from its filename, not its first line, so the nonce is looked for
        // where the title actually comes from.
        val book = runBlocking { api.books(limit = 50, sort = "addedAt", order = "desc") }
            .items.first { it.title.contains(nonce) }
        val detail = runBlocking { api.book(book.id) }
        val file = detail.files.first()

        val downloadUrl = baseUrl!!.trimEnd('/') + "/api/v1/files/${file.id}/download"
        val downloader = RangeDownloader(authed, objects, segmentBytes = 64 * 1024)

        val result = runBlocking {
            downloader.download(
                fileId = file.id,
                expectedSha256 = file.sha256,
                urlSupplier = { start, ifRange ->
                    Request.Builder().url(downloadUrl).apply {
                        if (start != null) {
                            header("Range", "bytes=$start-")
                            ifRange?.let { header("If-Range", it) }
                        }
                    }.build()
                },
                onProgress = { _, _ -> },
            )
        }

        assertEquals("download failed: ${result.message}", RangeDownloader.Status.COMPLETED, result.status)
        assertNotNull(result.file)
        assertEquals(result.file!!.length(), detail.files.first().sizeBytes)
        assertEquals(objects.pathFor(result.sha256!!), result.file)

        // The server's own promise, checked independently of the client's arithmetic.
        val promised = authed.newCall(Request.Builder().url(downloadUrl).head().build()).execute().use {
            it.header("X-Content-Sha256")
        }
        assertEquals(promised, result.sha256)

        // Second call: satisfied from disk. The request counter is the proof — a status the client
        // computed for itself would also be reported on a code path that had just hit the network.
        val before = requests.get()
        val again = runBlocking {
            downloader.download(
                fileId = file.id,
                expectedSha256 = result.sha256,
                urlSupplier = { _, _ -> Request.Builder().url(downloadUrl).build() },
            )
        }
        assertEquals(RangeDownloader.Status.ALREADY_PRESENT, again.status)
        assertEquals(result.file, again.file)
        assertEquals("the cache hit made network requests", before, requests.get())

        root.deleteRecursively()
    }

    private fun retrofit(client: OkHttpClient): EbookdApi = Retrofit.Builder()
        .baseUrl(baseUrl!!)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(EbookdApi::class.java)
}
