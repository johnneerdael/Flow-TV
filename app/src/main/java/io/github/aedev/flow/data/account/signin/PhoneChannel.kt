package io.github.aedev.flow.data.account.signin

import io.github.aedev.flow.sync.crypto.SyncCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64

enum class PhoneKey { ENTER, TAB, BACKSPACE }

sealed interface PhoneInput {
    data class Text(
        val value: String,
    ) : PhoneInput {
        override fun toString(): String = "Text(${value.length} chars)"
    }

    data class Key(
        val key: PhoneKey,
    ) : PhoneInput
}

@Serializable
data class PhoneEnvelope(
    val n: String,
    val c: String,
)

@Serializable
data class PhoneStatus(
    val step: String,
    val done: Boolean = false,
)

@Serializable
private data class PhoneInputPayload(
    val seq: Long,
    val type: String,
    val value: String,
)

class PhoneChannelRejected(
    reason: String,
) : Exception(reason)

/**
 * AES-256-GCM channel between the TV and the phone page. The key only ever travels in the QR
 * fragment, which browsers never send over the network.
 */
class PhoneChannel(
    private val sessionId: ByteArray,
    private val key: ByteArray,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private var lastSeq = 0L
    private var boundHost: String? = null

    val fragment: String get() = "${encode(sessionId)}.${encode(key)}"

    @Synchronized
    fun open(
        envelope: PhoneEnvelope,
        remoteHost: String,
    ): PhoneInput {
        boundHost?.let { if (it != remoteHost) throw PhoneChannelRejected("bound to another device") }
        val plain =
            try {
                val nonce = decode(envelope.n)
                val sealed = decode(envelope.c)
                // Providers differ on malformed input (JDK 17 throws ProviderException), so reject it up front.
                if (nonce.size != SyncCrypto.NONCE_LEN || sealed.size < SyncCrypto.TAG_LEN) throw PhoneChannelRejected("bad size")
                SyncCrypto.open(key, nonce, sealed, aad(CLIENT_TO_HOST))
            } catch (e: GeneralSecurityException) {
                throw PhoneChannelRejected("bad seal")
            } catch (e: ProviderException) {
                throw PhoneChannelRejected("bad seal")
            } catch (e: IllegalArgumentException) {
                throw PhoneChannelRejected("bad encoding")
            }
        val payload =
            try {
                json.decodeFromString(PhoneInputPayload.serializer(), plain.decodeToString())
            } catch (e: SerializationException) {
                throw PhoneChannelRejected("bad payload")
            } finally {
                plain.fill(0)
            }
        if (payload.seq <= lastSeq) throw PhoneChannelRejected("replayed")
        val input =
            when (payload.type) {
                TYPE_TEXT -> {
                    PhoneInput.Text(payload.value)
                }

                TYPE_KEY -> {
                    PhoneInput.Key(PhoneKey.entries.firstOrNull { it.name == payload.value } ?: throw PhoneChannelRejected("unknown key"))
                }

                else -> {
                    throw PhoneChannelRejected("unknown type")
                }
            }
        lastSeq = payload.seq
        boundHost = remoteHost
        return input
    }

    fun seal(status: PhoneStatus): PhoneEnvelope {
        val nonce = SyncCrypto.randomNonce()
        val sealed =
            SyncCrypto.seal(
                key,
                nonce,
                json.encodeToString(PhoneStatus.serializer(), status).encodeToByteArray(),
                aad(HOST_TO_CLIENT),
            )
        return PhoneEnvelope(encode(nonce), encode(sealed))
    }

    fun destroy() {
        key.fill(0)
    }

    private fun aad(direction: String): ByteArray = sessionId + direction.encodeToByteArray()

    companion object {
        private const val CLIENT_TO_HOST = "c2s"
        private const val HOST_TO_CLIENT = "s2c"
        private const val TYPE_TEXT = "text"
        private const val TYPE_KEY = "key"
        private const val SESSION_ID_BYTES = 16
        private const val KEY_BYTES = 32
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun create(): PhoneChannel = PhoneChannel(SyncCrypto.randomBytes(SESSION_ID_BYTES), SyncCrypto.randomBytes(KEY_BYTES))

        private fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

        private fun decode(text: String): ByteArray = decoder.decode(text)
    }
}
