package dev.ebookd.core.net

import dev.ebookd.core.data.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Points every request at whatever address the user configured, without rebuilding Retrofit.
 *
 * Retrofit insists on a base URL when it is constructed, but the real one is user input that can
 * change at any time. Rewriting the host here — rather than caching a Retrofit per address — keeps
 * a single API instance alive, so a screen that already holds a reference cannot keep talking to
 * the previous server after the address is changed.
 *
 * The placeholder host is `.invalid` on purpose: it is reserved by RFC 2606, so a request that
 * escapes this interceptor before an address is configured fails immediately with a DNS error
 * instead of reaching something on the network by accident.
 */
class BaseUrlInterceptor(private val settings: SettingsStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val base = runBlocking { settings.baseUrl.first() }?.toHttpUrlOrNull()
            ?: return chain.proceed(request)

        val rewritten = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            // The base may carry a path prefix (a reverse proxy under /ebookd/), so the base path
            // and the request path are joined rather than one replacing the other.
            .encodedPath(base.encodedPath.trimEnd('/') + request.url.encodedPath)
            .build()

        return chain.proceed(request.newBuilder().url(rewritten).build())
    }
}
