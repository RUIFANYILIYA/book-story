package dev.ebookd.core.net

import dev.ebookd.core.security.TokenStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/**
 * Sign-in and sign-out as the UI needs them.
 *
 * The seam this creates is deliberate: `:core` knows the wire (HTTP status, the closed error-code
 * enum, which failures are permanent), and the screens know the words. That is what keeps
 * `retrofit2.HttpException` — and the decision of whether a 429 means "locked" or "slow down" —
 * out of the UI layer, where it would otherwise be re-derived from status codes in every screen.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: EbookdApi,
    private val tokens: TokenStore,
    private val json: Json,
) {

    sealed interface SignInResult {
        data class Success(val user: PublicUserDto) : SignInResult
        data object InvalidCredentials : SignInResult
        data object AccountLocked : SignInResult
        data object AccountDisabled : SignInResult
        data object RateLimited : SignInResult
        data object UpgradeRequired : SignInResult

        /** Something the server said that this client does not have a sentence for yet. */
        data class Unexpected(val status: Int, val code: String?) : SignInResult

        /** Never reached the server, or the answer never arrived. */
        data object Unreachable : SignInResult
    }

    suspend fun signIn(username: String, password: String, device: DeviceDto): SignInResult {
        return try {
            val grant = api.login(LoginRequestDto(username = username, password = password, device = device))
            tokens.save(grant)
            SignInResult.Success(grant.user)
        } catch (failure: HttpException) {
            when (val code = errorCode(failure)) {
                "invalid_credentials" -> SignInResult.InvalidCredentials
                "account_locked" -> SignInResult.AccountLocked
                "account_disabled" -> SignInResult.AccountDisabled
                "too_many_requests" -> SignInResult.RateLimited
                "upgrade_required" -> SignInResult.UpgradeRequired
                else -> when (failure.code()) {
                    401, 403 -> SignInResult.InvalidCredentials
                    429 -> SignInResult.RateLimited
                    else -> SignInResult.Unexpected(failure.code(), code)
                }
            }
        } catch (_: IOException) {
            SignInResult.Unreachable
        }
    }

    /**
     * Ends this device's lineage server-side and forgets the credentials locally.
     *
     * The order matters: the server call needs the refresh token, so the local store is only
     * cleared after it — and if the call fails because the network is gone, the local credentials
     * are still cleared, because "sign out" on a phone must mean the phone no longer holds them.
     */
    suspend fun signOut() {
        val refresh = tokens.refreshToken()
        if (refresh != null) {
            runCatching { api.logout(RefreshRequestDto(refresh)) }
        }
        tokens.clear()
    }

    private fun errorCode(failure: HttpException): String? = runCatching {
        val body = failure.response()?.errorBody()?.string().orEmpty()
        json.decodeFromString(ErrorEnvelopeDto.serializer(), body).error.code
    }.getOrNull()
}
