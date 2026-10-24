package dev.ebookd.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The client's wire types against a *running* server, rather than against a mock.
 *
 * This is the one test that can catch the class of mistake a `MockWebServer` cannot: a DTO field
 * spelled differently from the server's, a path that the contract documents but the router serves
 * under another name, a protocol number that does not match. It is skipped unless the environment
 * provides a server, because CI has none — and skipped loudly, via an assumption, so it can never
 * be mistaken for a pass.
 *
 * Run it with the dev server up:
 *   EBOOKD_E2E_URL=http://127.0.0.1:8787 EBOOKD_E2E_PASSWORD=... ./gradlew :core:testDebugUnitTest
 */
class LiveServerContractTest {

    private val baseUrl: String? = System.getProperty("ebookd.e2e.url")
    private val username: String = System.getProperty("ebookd.e2e.user") ?: "admin"
    private val password: String = System.getProperty("ebookd.e2e.password").orEmpty()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun api(client: OkHttpClient): EbookdApi = Retrofit.Builder()
        .baseUrl(baseUrl!!)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(EbookdApi::class.java)

    @Test
    fun `discovery, sign-in, rotation, self-inspection and revocation all behave as the app assumes`() {
        assumeTrue("no server configured; set -Debookd.e2e.url", !baseUrl.isNullOrBlank())
        assumeTrue("no password configured; set -Debookd.e2e.password", password.isNotEmpty())

        val client = OkHttpClient()
        val api = api(client)

        // Discovery: the protocol number is the app's contract check, and the feature map is what
        // the shelf will branch on later.
        val meta = runBlocking { api.meta(1) }
        assertEquals("app and server disagree on the wire protocol", MetaDto.PROTOCOL, meta.protocol)
        assertEquals("v1", meta.apiVersion)
        assertTrue(meta.features.containsKey("rangeDownloads"))

        // Sign-in issues a full grant.
        val grant = runBlocking {
            api.login(LoginRequestDto(username, password, DeviceDto(label = "e2e-test", platform = "android", clientVersion = "test")))
        }
        assertTrue(grant.accessToken.isNotBlank())
        assertTrue(grant.refreshToken.isNotBlank())
        assertEquals("Bearer", grant.tokenType)

        fun clientWith(token: String): EbookdApi = api(
            client.newBuilder()
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
                }
                .build(),
        )

        // The access token authenticates, and this is where a DTO field spelled differently from
        // the server's would show up as a null instead of a name.
        val me = runBlocking { clientWith(grant.accessToken).me() }
        assertEquals(username, me.user.username)
        assertTrue(me.capabilities.isNotEmpty())

        // Rotation: single-use, and the *new* access token works immediately.
        val rotated = runBlocking { api.refresh(RefreshRequestDto(grant.refreshToken)) }
        assertNotEquals(grant.refreshToken, rotated.refreshToken)
        assertEquals(username, runBlocking { clientWith(rotated.accessToken).me() }.user.username)

        // Sign-out ends the lineage for real.
        runBlocking { clientWith(rotated.accessToken).logout(RefreshRequestDto(rotated.refreshToken)) }
        val afterLogout = runCatching { runBlocking { api.refresh(RefreshRequestDto(rotated.refreshToken)) } }
        assertTrue("logout did not revoke the lineage", afterLogout.isFailure)

        // Replay detection, probed last and on its own lineage, because it is *destructive*: a spent
        // token being presented again means the lineage was copied, and the server revokes the whole
        // family — including the access token that a naive test would still be using. Getting this
        // order wrong is how the first version of this test failed, and why the offline design can
        // trust that a stolen refresh token cannot silently coexist with the real device.
        val second = runBlocking {
            api.login(LoginRequestDto(username, password, DeviceDto(label = "e2e-replay", platform = "android")))
        }
        val secondRotated = runBlocking { api.refresh(RefreshRequestDto(second.refreshToken)) }
        val replay = runCatching { runBlocking { api.refresh(RefreshRequestDto(second.refreshToken)) } }
        assertTrue("a spent refresh token was accepted", replay.isFailure)
        val afterReplay = runCatching { runBlocking { clientWith(secondRotated.accessToken).me() } }
        assertTrue("replay did not revoke the lineage it belonged to", afterReplay.isFailure)
    }

    /**
     * The catalogue DTOs, against real rows rather than a mock.
     *
     * The book is uploaded here rather than assumed to exist, because the interesting assertions are
     * about a *parsed* book: a title the server derived from the filename, a format list, a file
     * entry whose id is the download path. A hand-written JSON stub would let the DTOs drift from
     * the server's actual naming without ever failing.
     */
    @Test
    fun `the catalogue returns a list page and a detail the shelf and the detail screen can render`() {
        assumeTrue("no server configured; set -Debookd.e2e.url", !baseUrl.isNullOrBlank())
        assumeTrue("no password configured; set -Debookd.e2e.password", password.isNotEmpty())

        val client = OkHttpClient()
        val anonymous = api(client)
        val grant = runBlocking {
            anonymous.login(LoginRequestDto(username, password, DeviceDto(label = "e2e-catalogue", platform = "android")))
        }
        val authed = client.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder().header("Authorization", "Bearer ${grant.accessToken}").build(),
                )
            }
            .build()
        val api = api(authed)

        val sample = buildString {
            appendLine("第一章 起风")
            appendLine()
            appendLine("夜色从海面漫上来，灯塔的光一圈一圈扫过水面。这是端到端契约测试上传的样本。")
            appendLine()
            appendLine("第二章 落雨")
            appendLine()
            appendLine("雨点敲在铁皮屋顶上，声音密得像有人在上面走动。长度足够，不会被合并掉。")
        }
        val upload = Request.Builder()
            .url(baseUrl!! + "api/v1/uploads")
            .header("Authorization", "Bearer ${grant.accessToken}")
            .post(
                MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file",
                        "ebookd-e2e-sample.txt",
                        sample.toRequestBody("text/plain".toMediaType()),
                    )
                    .build(),
            )
            .build()
        val receipt = runBlocking(Dispatchers.IO) {
            client.newCall(upload).execute().use { response -> response.code to response.body?.string().orEmpty() }
        }
        // 409 duplicate_content is a success for this test's purpose: the server dedupes by hash, so
        // the second run of this test against the same library finds the book already there. A test
        // that only passes on a fresh database would rot the first time it is re-run.
        val accepted = receipt.first in 200..299 ||
            (receipt.first == 409 && receipt.second.contains("duplicate_content"))
        assertTrue("upload failed: HTTP ${receipt.first} ${receipt.second}", accepted)

        val page = runBlocking { api.books(limit = 50, sort = "addedAt", order = "desc") }
        assertTrue("the freshly uploaded book is not in the catalogue", page.items.isNotEmpty())
        val book = page.items.first { it.title.contains("e2e-sample") }
        assertTrue("title came back empty", book.title.isNotBlank())
        assertTrue("format list is empty for a txt book", book.formats.contains("txt"))
        assertEquals(1, book.fileCount)

        val detail = runBlocking { api.book(book.id) }
        assertEquals(book.id, detail.book.id)
        assertTrue("detail carries no files", detail.files.isNotEmpty())
        assertTrue("detail does not list the txt file", detail.files.any { it.format == "txt" })
        // The file id is both the download path and the cache key, so an empty one would break the
        // downloader before it was written.
        assertTrue("file id is empty", detail.files.first().id.isNotBlank())
    }
}
