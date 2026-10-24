package dev.ebookd.core.net

import dev.ebookd.core.security.TokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Attaches the access token and, when the server says it expired, refreshes once and replays the
 * request.
 *
 * Three rules keep this from becoming a loop, a stampede, or a lockout:
 *
 *  - A request is retried **once**. A second 401 after a successful refresh means something else is
 *    wrong, and retrying again would hammer a server that is already answering "no".
 *  - The sign-in and refresh calls are never candidates. A wrong password is a 401 like any other,
 *    and refreshing on it would rotate the stored lineage because the reader mistyped.
 *  - A 401 on a request that carried **no** token still refreshes. The access token is held in
 *    memory only, so the first request after a process restart always arrives here unauthenticated;
 *    treating "no token to retry with" as a reason to give up meant every restart looked like a
 *    sign-out even with a valid refresh token on disk.
 *
 * The refresh is blocking on purpose: OkHttp interceptors are synchronous, and the alternative —
 * returning the 401 and letting every caller deal with it — is how "just this screen fails" bugs
 * are born.
 */
class AuthInterceptor(
    private val tokens: TokenStore,
    private val refresher: TokenRefresher,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val ownsItsCredential = original.header("Authorization") != null || isAuthCall(original)
        val used = tokens.accessToken()

        val request = if (ownsItsCredential || used == null) {
            original
        } else {
            original.newBuilder().header("Authorization", "Bearer $used").build()
        }

        val response = chain.proceed(request)
        if (response.code != 401 || ownsItsCredential) return response

        // The refresh token is single-use, and an unnecessary rotation looks like a replay to the
        // server, which revokes the whole lineage. `refresh` re-checks under its own lock whether
        // somebody already rotated *this* token, so a caller that arrives late makes no second call.
        runBlocking { refresher.refresh(used) }
        val fresh = tokens.accessToken()

        // Nothing improved (no refresh token, rejected refresh, or the same token came back):
        // returning the 401 is the honest answer, and it is also what stops a retry loop.
        if (fresh == null || fresh == used) return response

        response.close()
        return chain.proceed(original.newBuilder().header("Authorization", "Bearer $fresh").build())
    }

    /**
     * The two endpoints whose 401 is the *answer*, not a credential to repair. Matched on path
     * because both are part of the v1 contract and neither is a candidate for replay.
     */
    private fun isAuthCall(request: Request): Boolean =
        request.url.encodedPath.let { it.endsWith("/auth/refresh") || it.endsWith("/auth/login") }
}
