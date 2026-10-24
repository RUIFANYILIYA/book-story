package dev.ebookd.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Address input is where a typo and a network fault look identical, so the rules are pinned here
 * rather than trusted to the screen.
 */
class ServerAddressTest {

    private fun valid(input: String, allowLoopback: Boolean = false): String {
        val result = ServerAddress.normalize(input, allowLoopback)
        assertTrue("expected $input to be valid, got $result", result is ServerAddress.Result.Valid)
        return (result as ServerAddress.Result.Valid).baseUrl
    }

    private fun invalid(input: String, allowLoopback: Boolean = false): String {
        val result = ServerAddress.normalize(input, allowLoopback)
        assertTrue("expected $input to be invalid, got $result", result is ServerAddress.Result.Invalid)
        return (result as ServerAddress.Result.Invalid).reason
    }

    @Test
    fun `a LAN address becomes a base URL with exactly one trailing slash`() {
        assertEquals("http://192.168.86.4:8787/", valid("http://192.168.86.4:8787"))
        assertEquals("http://192.168.86.4:8787/", valid("  http://192.168.86.4:8787/  "))
        assertEquals("https://ebook.example.com/", valid("https://ebook.example.com"))
    }

    @Test
    fun `a pasted api path is folded into the base instead of doubling it`() {
        // The user copied the URL from a browser address bar; `.../api/api/v1/meta` must not exist.
        assertEquals("http://192.168.86.4:8787/", valid("http://192.168.86.4:8787/api"))
        assertEquals("http://192.168.86.4:8787/", valid("http://192.168.86.4:8787/api/v1/meta/"))
    }

    @Test
    fun `a path prefix survives, because a reverse proxy may put the server under one`() {
        assertEquals("https://example.com/ebookd/", valid("https://example.com/ebookd"))
    }

    @Test
    fun `localhost is refused with a hint that names the fix`() {
        val reason = invalid("http://localhost:8787")
        assertTrue(reason.contains("局域网 IP"))
        invalid("http://127.0.0.1:8787")
        invalid("http://[::1]:8787")
        // ...but the emulator, which reaches the host through 10.0.2.2, is a legitimate address.
        assertEquals("http://10.0.2.2:8787/", valid("http://10.0.2.2:8787"))
    }

    @Test
    fun `missing scheme and empty host are named for what they are`() {
        assertTrue(invalid("192.168.86.4:8787").contains("http://"))
        assertTrue(invalid("").isNotEmpty())
        assertTrue(invalid("http://").isNotEmpty())
    }
}
