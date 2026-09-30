package io.github.aedev.flow.data.account.signin

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync.crypto.SyncCrypto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Test
import java.util.Base64

class PhoneInputServerTest {
    private val received = mutableListOf<PhoneInput>()
    private var captures = 0
    private val server =
        PhoneInputServer(
            channel = PhoneChannel(VECTOR_SESSION_ID.copyOf(), VECTOR_KEY.copyOf()),
            readAsset = { path -> "asset:$path".encodeToByteArray() },
            status = { PhoneStatus(step = "Welcome") },
            onInput = { received += it },
            captureFrame = {
                captures++
                PhoneFrame(320, 240, "fixture-pixels")
            },
        )
    private val http = OkHttpClient()

    @After fun tearDown() = server.stop()

    private fun start(): String = "http://127.0.0.1:${runBlocking { server.start("127.0.0.1") }}"

    private fun post(
        base: String,
        body: String,
    ) = http
        .newCall(
            Request
                .Builder()
                .url("$base/input")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()

    @Test
    fun `serves the page and its scripts with no-store`() {
        val base = start()
        http.newCall(Request.Builder().url("$base/").build()).execute().use {
            assertThat(it.code).isEqualTo(200)
            assertThat(it.header("Cache-Control")).isEqualTo("no-store")
            assertThat(it.body!!.string()).isEqualTo("asset:account-signin/index.html")
        }
        http.newCall(Request.Builder().url("$base/noble-ciphers.js").build()).execute().use {
            assertThat(it.body!!.string()).isEqualTo("asset:account-signin/noble-ciphers-2.4.0.min.js")
        }
    }

    @Test
    fun `a sealed input is delivered`() {
        val base = start()
        val envelope =
            Json.encodeToString(
                PhoneEnvelope.serializer(),
                phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "text", "me@example.com"),
            )
        post(base, envelope).use { assertThat(it.code).isEqualTo(204) }
        assertThat(received).containsExactly(PhoneInput.Text("me@example.com"))
    }

    @Test
    fun `garbage is refused with 403`() {
        val base = start()
        post(base, "not json").use { assertThat(it.code).isEqualTo(403) }
        post(base, """{"n":"AAAA","c":"AAAA"}""").use { assertThat(it.code).isEqualTo(403) }
        assertThat(received).isEmpty()
    }

    @Test
    fun `status is sealed`() {
        val base = start()
        val body = http.newCall(Request.Builder().url("$base/status").build()).execute().use { it.body!!.string() }
        val envelope = Json.decodeFromString(PhoneEnvelope.serializer(), body)
        val d = Base64.getUrlDecoder()
        val plain = SyncCrypto.open(VECTOR_KEY, d.decode(envelope.n), d.decode(envelope.c), VECTOR_SESSION_ID + "s2c".encodeToByteArray())
        assertThat(plain.decodeToString()).contains("Welcome")
    }

    @Test
    fun `a viewport is captured only for an authenticated frame request and returned encrypted`() {
        val base = start()

        fun frame(body: String) =
            http
                .newCall(
                    Request
                        .Builder()
                        .url("$base/frame")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        frame("{}").use { assertThat(it.code).isEqualTo(403) }
        assertThat(captures).isEqualTo(0)
        val body = Json.encodeToString(PhoneEnvelope.serializer(), phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 1, "frame", ""))
        frame(body).use { response ->
            assertThat(response.code).isEqualTo(200)
            assertThat(response.header("Cache-Control")).isEqualTo("no-store")
            val sealed = response.body!!.string()
            assertThat(sealed).doesNotContain("fixture-pixels")
            val envelope = Json.decodeFromString(PhoneEnvelope.serializer(), sealed)
            val d = Base64.getUrlDecoder()
            val plain =
                SyncCrypto.open(
                    VECTOR_KEY,
                    d.decode(envelope.n),
                    d.decode(envelope.c),
                    VECTOR_SESSION_ID + "s2c".encodeToByteArray(),
                )
            assertThat(
                Json.decodeFromString(PhoneFrameReply.serializer(), plain.decodeToString()),
            ).isEqualTo(PhoneFrameReply(1, PhoneFrame(320, 240, "fixture-pixels")))
        }
        frame(body).use { assertThat(it.code).isEqualTo(403) }
        val next = Json.encodeToString(PhoneEnvelope.serializer(), phoneSeal(VECTOR_SESSION_ID, VECTOR_KEY, 2, "frame", ""))
        frame(next).use { assertThat(it.code).isEqualTo(429) }
        assertThat(captures).isEqualTo(1)
        assertThat(received).isEmpty()
    }
}
