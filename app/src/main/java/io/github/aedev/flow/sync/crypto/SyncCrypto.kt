package io.github.aedev.flow.sync.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * FLOW-SYNC/1 cryptography. Implements the byte-exact scheme so that frames sealed by the Rust desktop
 * (RustCrypto `aes-gcm` / `hkdf` / `hmac`) open here and vice-versa.
 *
 * Primitives (all standard JCE, hardware-accelerated where available):
 * - HKDF-SHA256 (RFC 5869) over `javax.crypto.Mac("HmacSHA256")` → directional keys.
 * - AES-256-GCM (`AES/GCM/NoPadding`, 128-bit tag) with a 12-byte random nonce per frame.
 * - HMAC-SHA256 → 6-digit SAS.
 */
object SyncCrypto {
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val GCM_TAG_BITS = 128

    private val LABEL_SAS = "flow-sync/1 sas".toByteArray(Charsets.US_ASCII)

    private val secureRandom = SecureRandom()

    // --- randomness ---

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

    fun randomNonce(): ByteArray = randomBytes(NONCE_LEN)

    // --- SAS ---

    /**
     * 6-digit Short Authentication String:
     * `num = 31-bit BE of HMAC-SHA256(K_master, "flow-sync/1 sas" ∥ sid)[0..4]; num % 1e6`.
     */
    fun sas(
        masterKey: ByteArray,
        sessionId: ByteArray,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        mac.update(LABEL_SAS)
        mac.update(sessionId)
        val d = mac.doFinal()
        val num =
            ((d[0].toInt() and 0x7F) shl 24) or
                ((d[1].toInt() and 0xFF) shl 16) or
                ((d[2].toInt() and 0xFF) shl 8) or
                (d[3].toInt() and 0xFF)
        return (num % 1_000_000).toString().padStart(6, '0')
    }

    // --- AES-256-GCM ---

    /** Seal: returns `ciphertext ∥ tag(16)` */
    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /**
     * Open: input is `ciphertext ∥ tag(16)`. Throws [javax.crypto.AEADBadTagException] (a
     * [javax.crypto.BadPaddingException] subtype) if the tag/AAD do not verify — the caller MUST
     * treat that as "drop the connection".
     */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextAndTag)
    }
}
