package io.github.aedev.flow.data.account.signin

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SignInAssetsTest {
    private val dir = File("src/main/assets/account-signin")

    @Test
    fun `the bundled cipher library is the pinned unmodified build`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(File(dir, "noble-ciphers-2.4.0.min.js").readBytes())
        assertThat(digest.joinToString("") { "%02x".format(it) })
            .isEqualTo("705257deef2bc0b2002e9d6359c41c9d4acea9afe40e02cc8fbc0d2bd0311382")
    }

    @Test
    fun `the page loads only same-origin scripts`() {
        val html = File(dir, "index.html").readText()
        assertThat(Regex("""<script[^>]+src="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList())
            .containsExactly("/noble-ciphers.js", "/app.js")
            .inOrder()
        assertThat(html).doesNotContain("http://")
        assertThat(html).doesNotContain("https://")
    }

    @Test
    fun `the page script uses the channel directions and never logs`() {
        val js = File(dir, "app.js").readText()
        assertThat(js).contains("'c2s'")
        assertThat(js).contains("'s2c'")
        assertThat(js).doesNotContain("console.")
        assertThat(File(dir, "NOBLE_CIPHERS_LICENSE.txt").readText()).contains("Paul Miller")
    }
}
