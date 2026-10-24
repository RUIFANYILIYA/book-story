package dev.ebookd.core.net

import dev.ebookd.core.security.TokenStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The auth stack, against a real HTTP server.
 *
 * These are the tests that matter most in the whole shell, because the failure they prevent is
 * invisible in code review: refresh tokens here are single-use, and a second refresh with an
 * already-rotated token makes the server (correctly) revoke every session for that device. A client
 * with a naive "on 401, refresh" rule logs the reader out exactly when several screens load at once.
 */
class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var api: EbookdApi
    private lateinit var tokens: FakeTokenStore
    private lateinit var scope: CoroutineScope
    private val refreshCalls = AtomicInteger()

    /** In memory, like the real store's access half — the Keystore half needs a device. */
    private class FakeTokenStore : TokenStore {
        private var access: String? = null
        private var refresh: String? = null
        private var device: String? = null

        override fun accessToken(): String? = access
        override fun deviceId(): String? = device
        override fun refreshToken(): String? = refresh

        override fun save(grant: SessionGrantDto) {
            access = grant.accessToken
            refresh = grant.refreshToken
            device = grant.deviceId
        }

        override fun clear() {
            access = null
            refresh = null
        }

        /** A process restart: the refresh half survived on disk, the access half was never on disk. */
        fun restoreAfterRestart(refreshToken: String) {
            access = null
            refresh = refreshToken
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tokens = FakeTokenStore()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val json = Json { ignoreUnknownKeys = true }
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokens, TokenRefresher(tokens, { api }, scope)))
            .build()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(EbookdApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun grant(access: String, refresh: String) = SessionGrantDto(
        accessToken = access,
        refreshToken = refresh,
        deviceId = "dev-1",
        user = PublicUserDto(id = "u1", username = "admin", role = "admin"),
    )

    /** Refresh succeeds and hands back a new pair; the protected route only accepts the new token. */
    private fun respondWithRotation(refreshStatus: Int = 200) {
        refreshCalls.set(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/api/v1/auth/refresh") -> {
                    refreshCalls.incrementAndGet()
                    if (refreshStatus == 200) {
                        MockResponse().setResponseCode(200).setBody(
                            """{"accessToken":"new-access","refreshToken":"r2","deviceId":"dev-1",
                               "user":{"id":"u1","username":"admin","role":"admin"}}""".trimIndent(),
                        )
                    } else {
                        MockResponse().setResponseCode(refreshStatus)
                            .setBody("""{"error":{"code":"unauthenticated","message":"no"}}""")
                    }
                }
                request.getHeader("Authorization") == "Bearer new-access" ->
                    MockResponse().setResponseCode(200).setBody(
                        """{"user":{"id":"u1","username":"admin","role":"admin"},"capabilities":[]}""",
                    )
                else -> MockResponse().setResponseCode(401)
                    .setBody("""{"error":{"code":"token_expired","message":"expired"}}""")
            }
        }
    }

    @Test
    fun `a 401 refreshes once and replays the request with the new token`() {
        tokens.save(grant("old-access", "r1"))
        respondWithRotation()

        val me = runBlocking { api.me() }

        assertEquals("admin", me.user.username)
        assertEquals(1, refreshCalls.get())
        assertEquals("new-access", tokens.accessToken())
        assertEquals("r2", tokens.refreshToken())
    }

    @Test
    fun `four requests that all see a 401 rotate the token exactly once`() {
        tokens.save(grant("old-access", "r1"))
        respondWithRotation()

        runBlocking {
            (1..4).map { async(Dispatchers.IO) { api.me() } }.awaitAll()
        }

        // Two rotations would mean four lineage revocations in production: this is the assertion
        // the whole single-flight design exists for.
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `a rejected refresh clears the credentials and the 401 reaches the caller`() {
        tokens.save(grant("old-access", "r1"))
        respondWithRotation(refreshStatus = 401)

        val failure = runCatching { runBlocking { api.me() } }.exceptionOrNull()

        assertTrue(failure is HttpException && (failure as HttpException).code() == 401)
        assertEquals(1, refreshCalls.get())
        assertNull(tokens.refreshToken())
        assertNull(tokens.accessToken())
    }

    @Test
    fun `a refresh that fails on the network keeps the credential for a later attempt`() {
        tokens.save(grant("old-access", "r1"))
        respondWithRotation(refreshStatus = 500)

        val failure = runCatching { runBlocking { api.me() } }.exceptionOrNull()

        assertTrue(failure is HttpException && (failure as HttpException).code() == 401)
        // A server that is restarting must not sign the reader out; only an explicit rejection does.
        assertEquals("r1", tokens.refreshToken())
    }

    @Test
    fun `the first request after a process restart refreshes even though it carried no token`() {
        // The access half lives in memory, so this is what every cold start looks like. Treating
        // "nothing to retry with" as a reason not to refresh made a restart indistinguishable from a
        // sign-out, with a valid refresh token sitting in the Keystore-backed file.
        tokens.restoreAfterRestart("r1")
        respondWithRotation()

        val me = runBlocking { api.me() }

        assertEquals("admin", me.user.username)
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `a wrong password is an answer, not a credential to repair`() {
        // A sign-in 401 looks exactly like an expired-token 401 to the interceptor. Refreshing on it
        // would rotate the stored lineage because the reader mistyped their password.
        tokens.restoreAfterRestart("r1")
        respondWithRotation()

        val failure = runCatching {
            runBlocking { api.login(LoginRequestDto("admin", "wrong-password")) }
        }.exceptionOrNull()

        assertTrue(failure is HttpException)
        assertEquals(0, refreshCalls.get())
        assertEquals("r1", tokens.refreshToken())
    }
}
