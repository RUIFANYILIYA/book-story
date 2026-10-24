package dev.ebookd.core.net

import java.net.URI

/**
 * Validates and normalises what the user typed into the server-address field.
 *
 * This is the one place in the app where a typo is indistinguishable from a network problem, so the
 * rules are explicit and the rejection names the fix. The two that matter most:
 *
 *  - `localhost` is rejected on a phone. It is always "valid", always resolves, and always fails —
 *    and the failure looks like a server problem rather than a typo. The hint has to name the
 *    address the user actually means (the LAN IP of the machine running the server).
 *  - A pasted address often carries a path (`.../api`, or a trailing slash). Accepting it as-is
 *    would produce `.../api/api/v1/meta`, so it is normalised into a base URL that ends in exactly
 *    one slash.
 */
object ServerAddress {

    sealed interface Result {
        data class Valid(val baseUrl: String) : Result
        data class Invalid(val reason: String) : Result
    }

    /** Hosts that mean "this device" and therefore cannot be the library server on a handset. */
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

    /** The emulator's door to the host machine, which is a legitimate thing to type there. */
    const val EMULATOR_HOST = "10.0.2.2"

    fun normalize(input: String, allowLoopback: Boolean = false): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid("请输入服务器地址")

        // Checked before parsing, because `192.168.86.4:8787` does not fail URI parsing in a way
        // that says "you forgot the scheme" — it parses, with a nonsense scheme, and produces a
        // message about the host instead. The hint the user needs is about the missing `http://`.
        if (!SCHEME_PREFIX.containsMatchIn(trimmed)) {
            return Result.Invalid("地址需要以 http:// 或 https:// 开头")
        }

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Result.Invalid("地址格式无法识别")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid("地址需要以 http:// 或 https:// 开头")
        }
        val host = uri.host
        if (host.isNullOrBlank()) return Result.Invalid("地址里没有主机名")

        if (!allowLoopback && host.lowercase() in LOOPBACK_HOSTS) {
            return Result.Invalid("手机上的 localhost 指的是手机自己；请填服务器的局域网 IP，例如 http://192.168.1.10:8787")
        }

        val port = if (uri.port >= 0) ":${uri.port}" else ""
        // A pasted address carries a path — often all the way to `/api/v1/meta`, which is what the
        // browser was showing. Everything from the first `api` segment is the API prefix, not part
        // of the server's location, so only what comes before it can be a reverse-proxy prefix.
        val rawPath = uri.path.orEmpty()
        val apiAt = rawPath.indexOf("/api")
        val prefix = (if (apiAt >= 0) rawPath.substring(0, apiAt) else rawPath).trimEnd('/')
        return Result.Valid("$scheme://$host$port$prefix/")
    }

    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
