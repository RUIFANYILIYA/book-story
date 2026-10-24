package dev.ebookd.core.data

import dev.ebookd.core.net.EbookdApi
import dev.ebookd.core.net.MeResponseDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException

/**
 * What this device's session is allowed to do, as the server says rather than as the app guesses.
 *
 * The role a client could compute locally is a lie waiting to happen: capabilities are per user and
 * the server is the only one enforcing them, so an action shown without permission is a button that
 * fails, and an action hidden without cause is a missing feature. This store keeps the answer to
 * `GET /auth/me` — which the app already parsed and then never used — and the UI asks it before
 * drawing a control.
 *
 * Empty is the honest default. Until the answer arrives, or when it cannot, the gated actions stay
 * hidden; that costs an admin a second of missing menu items, and it never costs a reader a
 * half-permitted write.
 */
@Singleton
class CapabilityStore @Inject constructor(
    private val api: EbookdApi,
) {

    private val _capabilities = MutableStateFlow<Set<String>>(emptySet())
    val capabilities: StateFlow<Set<String>> = _capabilities.asStateFlow()

    fun can(capability: String): Boolean = capability in _capabilities.value

    /**
     * Asks the server again, and keeps whatever it says — including "you are not signed in".
     *
     * A 401 clears the set rather than leaving it alone: capabilities kept from a previous session
     * would leave an admin's menu items on screen after they signed out on a shared device.
     */
    suspend fun refresh(): Result<MeResponseDto> =
        runCatching { api.me().also { _capabilities.value = it.capabilities.toSet() } }
            .onFailure { error -> if (error is HttpException && error.code() == 401) _capabilities.value = emptySet() }
}
