package dev.ebookd.core.net

import dev.ebookd.core.security.TokenStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * Rotates the refresh token, exactly once per expiry no matter how many requests discovered it.
 *
 * The single-flight part is not an optimisation. Refresh tokens here are *single use and
 * lineage-revoking*: if five parallel requests each notice a 401 and each refresh, four of them
 * present an already-rotated token, the server correctly concludes the lineage was copied and
 * revokes every session descended from that sign-in. A naive implementation logs the reader out
 * precisely when the app is busiest.
 */
class TokenRefresher(
    private val tokens: TokenStore,
    private val api: () -> EbookdApi,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var inFlight: Deferred<SessionGrantDto?>? = null

    /**
     * Rotates the lineage unless somebody already did it for this same [staleAccess].
     *
     * The check is repeated *inside* the mutex, which is the whole point: a caller that arrives
     * after the winner has saved its grant finds `inFlight` no longer active, and would otherwise
     * start a second rotation from the same rejected token. Concurrent callers are covered by the
     * in-flight job; back-to-back ones are only covered by this check.
     *
     * Returns null when no call was made — either because the store already moved on or because the
     * rotation failed. Read [TokenStore.accessToken] afterwards for whichever answer applies.
     */
    suspend fun refresh(staleAccess: String?): SessionGrantDto? {
        val job = mutex.withLock {
            if (tokens.accessToken() != staleAccess) return@withLock null
            inFlight?.takeIf { it.isActive } ?: scope.async { perform() }.also { inFlight = it }
        }
        return job?.await()
    }

    private suspend fun perform(): SessionGrantDto? {
        val current = tokens.refreshToken() ?: return null
        return try {
            val grant = api().refresh(RefreshRequestDto(current))
            tokens.save(grant)
            grant
        } catch (failure: HttpException) {
            // A rejected refresh is a *statement*, not a hiccup: the lineage was revoked (or the
            // token replayed). Keeping it would repeat the same failure forever, so the store is
            // cleared and the UI is expected to return to the sign-in screen.
            if (failure.code() == 401) tokens.clear()
            null
        } catch (_: IOException) {
            // Offline, DNS failure, server restarting: transient. Clearing here would sign the
            // reader out because their train entered a tunnel.
            null
        } catch (_: Exception) {
            null
        }
    }
}
