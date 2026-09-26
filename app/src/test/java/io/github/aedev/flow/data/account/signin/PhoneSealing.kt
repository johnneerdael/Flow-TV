package io.github.aedev.flow.data.account.signin

import io.github.aedev.flow.sync.crypto.SyncCrypto
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64

/** Seals exactly the way `assets/account-signin/app.js` does, for tests that play the phone. */
fun phoneSeal(
    sessionId: ByteArray,
    key: ByteArray,
    seq: Long,
    type: String,
    value: String,
): PhoneEnvelope {
    val nonce = SyncCrypto.randomNonce()
    val plain = """{"seq":$seq,"type":"$type","value":${JsonPrimitive(value)}}""".encodeToByteArray()
    val sealed = SyncCrypto.seal(key, nonce, plain, sessionId + "c2s".encodeToByteArray())
    val b64 = Base64.getUrlEncoder().withoutPadding()
    return PhoneEnvelope(b64.encodeToString(nonce), b64.encodeToString(sealed))
}

val VECTOR_SESSION_ID = ByteArray(16) { (0x10 + it).toByte() }
val VECTOR_KEY = ByteArray(32) { it.toByte() }
