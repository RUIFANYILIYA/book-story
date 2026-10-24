package dev.ebookd.engine.core

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.jsoup.Jsoup
import org.mozilla.universalchardet.UniversalDetector
import java.io.File

/**
 * M0 gate: proves the JDK-only build path works and that the three libraries the TXT engine is
 * built on actually resolve and behave, rather than merely appearing in a version catalog.
 * Replaced by real engine tests from M8 onward.
 */
class ToolchainProbeTest {

    @Test
    fun `shared fixture directory is reachable from the engine module`() {
        val path = System.getProperty("ebookd.fixtures")
            ?: error("Gradle test task did not expose ebookd.fixtures")
        val fixtures = File(path)
        assertThat(fixtures.isDirectory).isTrue()
        assertThat(File(fixtures, "txt-chapters").isDirectory).isTrue()
        assertThat(File(fixtures, "pagination").isDirectory).isTrue()
    }

    @Test
    fun `jsoup parses the xhtml subset used by epub content documents`() {
        val doc = Jsoup.parse(
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><div class="chapter"><h1>第一章</h1><p>夜色如墨。</p></div></body>
            </html>
            """.trimIndent(),
        )
        assertThat(doc.selectFirst("div.chapter > h1")!!.text()).isEqualTo("第一章")
        assertThat(doc.select("p").first()!!.text()).isEqualTo("夜色如墨。")
    }

    @Test
    fun `juniversalchardet detects gb18030 so a legacy Chinese txt does not render as mojibake`() {
        val sample = "夜色如墨，远处的山影沉沉，风从海面吹过来。".repeat(120)
        val bytes = sample.toByteArray(charset("GB18030"))

        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        val detected = detector.detectedCharset
        detector.reset()

        assertThat(detected).isNotNull()
        // The charset name the detector reports must be one the JVM can actually decode with,
        // and round-tripping it must reproduce the original text.
        assertThat(String(bytes, charset(detected!!))).isEqualTo(sample)
    }
}
