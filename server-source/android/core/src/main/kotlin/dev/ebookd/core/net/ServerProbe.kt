package dev.ebookd.core.net

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks a user-typed address "are you an ebookd server, and what time is it there".
 *
 * The reason this is its own class rather than a call through the generated API client is the
 * failure taxonomy. From the user's chair every failure is "it doesn't work", and the fixes are
 * completely different: a firewall blocks a port, a typo makes an unknown host, an old server
 * answers with something else, a self-signed certificate fails TLS. Each one is classified here so
 * the screen can say which it was.
 */
class ServerProbe(
    private val json: Json,
    /** Deliberately short: this call exists to give a fast verdict while a human waits. */
    private val timeout: Duration = Duration.ofSeconds(4),
) {

    sealed interface Outcome {
        data class Success(val baseUrl: String, val meta: MetaDto, val clockOffsetMillis: Long) : Outcome

        /** Reached something, but it is not this API (a router page, another service, a stale build). */
        data object NotEbookd : Outcome

        /** The server answered, but speaks a protocol this app does not. */
        data class ProtocolMismatch(val serverProtocol: Int) : Outcome

        /** The name did not resolve: almost always a typo or the wrong network. */
        data object DnsFailure : Outcome

        /** The name resolved but nothing accepted the connection: the port is closed or firewalled. */
        data object ConnectionRefused : Outcome

        data object Timeout : Outcome

        /** TLS handshake failed — typically a self-signed certificate on a LAN address. */
        data object TlsFailure : Outcome

        data class InvalidAddress(val reason: String) : Outcome
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(timeout)
        .connectTimeout(timeout)
        .readTimeout(timeout)
        .build()

    suspend fun probe(input: String, allowLoopback: Boolean = false): Outcome {
        val address = when (val result = ServerAddress.normalize(input, allowLoopback)) {
            is ServerAddress.Result.Invalid -> return Outcome.InvalidAddress(result.reason)
            is ServerAddress.Result.Valid -> result.baseUrl
        }
        val request = Request.Builder().url(address + "api/v1/meta").get().build()

        val body = try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()
                }
            }
        } catch (_: UnknownHostException) {
            return Outcome.DnsFailure
        } catch (_: ConnectException) {
            return Outcome.ConnectionRefused
        } catch (_: SocketTimeoutException) {
            return Outcome.Timeout
        } catch (_: InterruptedIOException) {
            return Outcome.Timeout
        } catch (_: SSLException) {
            return Outcome.TlsFailure
        } catch (_: Exception) {
            return Outcome.ConnectionRefused
        } ?: return Outcome.NotEbookd

        val meta = runCatching { json.decodeFromString(MetaDto.serializer(), body) }.getOrNull()
            ?: return Outcome.NotEbookd
        if (meta.apiVersion.isBlank()) return Outcome.NotEbookd
        if (meta.protocol != MetaDto.PROTOCOL) return Outcome.ProtocolMismatch(meta.protocol)

        val serverTime = runCatching { Instant.parse(meta.serverTime) }.getOrNull()
        val offset = serverTime?.let { it.toEpochMilli() - Instant.now().toEpochMilli() } ?: 0L
        return Outcome.Success(address, meta, offset)
    }
}
