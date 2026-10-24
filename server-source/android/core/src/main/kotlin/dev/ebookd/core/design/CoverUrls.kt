package dev.ebookd.core.design

/**
 * Turns the relative cover path the API returns into something an image loader can fetch.
 *
 * Covers are served from the same server (and therefore the same LAN address, certificate and
 * credentials) as the books themselves, and the response is authenticated — so the URL has to be
 * built against the *current* base URL rather than stored absolute: a reader who moves their server
 * to a new address must see covers again, not a cache full of URLs pointing at a machine that no
 * longer answers.
 */
fun absoluteCoverUrl(baseUrl: String?, relative: String?): String? {
    if (baseUrl.isNullOrBlank() || relative.isNullOrBlank()) return null
    if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
    return baseUrl.trimEnd('/') + "/" + relative.trimStart('/')
}
