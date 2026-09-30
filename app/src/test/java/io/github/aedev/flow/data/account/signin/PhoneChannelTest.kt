package io.github.aedev.flow.data.account.signin

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync.crypto.SyncCrypto
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class PhoneChannelTest {
    private fun channel() = PhoneChannel(VECTOR_SESSION_ID.copyOf(), VECTOR_KEY.copyOf())

    @Test
    fun `the fragment encodes session id and key`() {
        assertThat(channel().fragment)
            .isEqualTo("EBESExQVFhcYGRobHB0eHw.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    }

    @Test
    fun `opens an envelope sealed by noble-ciphers in the phone page`() {
        // Generated with @noble/ciphers 2.4.0 gcm() and the same AAD/nonce layout as app.js.
        val envelope =
            PhoneEnvelope(
                n = "oKGio6Slpqeoqaqr",
                c = "nToPSDTpOI5OR_Oqdx_i5FLYPGjmlW5O6m9K8xqJTyO6te6Tw01yfX3hummBTzORaonMakFfgHdfVw",
            )
        assertThat(channel().open(envelope, "192.168.1.20")).isEqualTo(PhoneInput.Text("héllo!@"))
    }

    @Test
    fun `unicode text survives the channel`() {
        val hostile = "p\"a'ss\\w</script> ö!@#"
        val input = channel().open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "text", hostile), "192.168.1.20")
        assertThat(input).isEqualTo(PhoneInput.Text(hostile))
    }

    @Test
    fun `keys are decoded`() {
        val input = channel().open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "key", "ENTER"), "192.168.1.20")
        assertThat(input).isEqualTo(PhoneInput.Key(PhoneKey.ENTER))
    }

    @Test
    fun `text can name the page field it is for, within the fields the phone is offered`() {
        val ch = channel()
        assertThat(ch.open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "text", "pw", field = 1), "192.168.1.20"))
            .isEqualTo(PhoneInput.Text("pw", field = 1))
        assertThrows(PhoneChannelRejected::class.java) {
            ch.open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 2, "text", "pw", field = MAX_PAGE_FIELDS), "192.168.1.20")
        }
        assertThrows(PhoneChannelRejected::class.java) {
            ch.open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 3, "text", "pw", field = -1), "192.168.1.20")
        }
    }

    @Test
    fun `a replayed envelope is rejected`() {
        val ch = channel()
        val envelope = phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 5, "text", "a")
        ch.open(envelope, "192.168.1.20")
        assertThrows(PhoneChannelRejected::class.java) { ch.open(envelope, "192.168.1.20") }
    }

    @Test
    fun `a second device is rejected after binding`() {
        val ch = channel()
        ch.open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "text", "a"), "192.168.1.20")
        assertThrows(PhoneChannelRejected::class.java) {
            ch.open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 2, "text", "b"), "192.168.1.99")
        }
    }

    @Test
    fun `a failed decrypt does not bind the device`() {
        val ch = channel()
        val tampered = phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "text", "a").let { it.copy(c = it.c.reversed()) }
        assertThrows(PhoneChannelRejected::class.java) { ch.open(tampered, "192.168.1.99") }
        assertThat(ch.open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 2, "text", "b"), "192.168.1.20")).isEqualTo(PhoneInput.Text("b"))
    }

    @Test
    fun `a wrong key is rejected`() {
        val envelope = phoneSeal(VECTOR_SESSION_ID, ByteArray(32) { 7 }, 1, "text", "a")
        assertThrows(PhoneChannelRejected::class.java) { channel().open(envelope, "192.168.1.20") }
    }

    @Test
    fun `an unknown key name is rejected`() {
        assertThrows(PhoneChannelRejected::class.java) {
            channel().open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "key", "ESCAPE"), "192.168.1.20")
        }
    }

    @Test
    fun `status is sealed for the phone direction`() {
        val envelope = channel().seal(PhoneStatus(step = "Enter your password", done = false))
        val decoder = Base64.getUrlDecoder()
        val plain =
            SyncCrypto.open(
                VECTOR_KEY,
                decoder.decode(envelope.n),
                decoder.decode(envelope.c),
                VECTOR_SESSION_ID + "s2c".encodeToByteArray(),
            )
        assertThat(plain.decodeToString()).isEqualTo("""{"step":"Enter your password","done":false,"actions":[],"fields":[]}""")
    }

    @Test
    fun `typed text is not exposed by toString`() {
        assertThat(PhoneInput.Text("hunter2").toString()).doesNotContain("hunter2")
    }

    @Test
    fun `a malformed envelope is rejected rather than crashing`() {
        listOf(
            PhoneEnvelope(n = "AAAA", c = "AAAA"),
            PhoneEnvelope(n = "oKGio6Slpqeoqaqr", c = "AAAA"),
            PhoneEnvelope(n = "", c = ""),
        ).forEach { bad ->
            assertThrows(PhoneChannelRejected::class.java) { channel().open(bad, "192.168.1.20") }
        }
    }

    @Test
    fun `clicks on the page's buttons are decoded`() {
        val input = channel().open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "click", "3"), "192.168.1.20")
        assertThat(input).isEqualTo(PhoneInput.Click(3))
    }

    @Test
    fun `a click outside the offered buttons is rejected`() {
        listOf("-1", "$MAX_PAGE_ACTIONS", "first").forEach { value ->
            assertThrows(PhoneChannelRejected::class.java) {
                channel().open(phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "click", value), "192.168.1.20")
            }
        }
    }
}
