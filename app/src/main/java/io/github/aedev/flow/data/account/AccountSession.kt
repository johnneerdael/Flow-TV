package io.github.aedev.flow.data.account

import io.github.aedev.flow.data.local.KeystoreSecretBox
import kotlinx.serialization.Serializable

@Serializable
data class AccountSession(
    val cookie: String,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val accountName: String? = null,
    val expired: Boolean = false,
) {
    override fun toString(): String = "AccountSession(accountName=$accountName, expired=$expired)"
}

interface SecretSealer {
    fun seal(plain: String): String

    fun open(stored: String?): String
}

internal object KeystoreSecretSealer : SecretSealer {
    override fun seal(plain: String): String = KeystoreSecretBox.seal(plain)

    override fun open(stored: String?): String = KeystoreSecretBox.open(stored)
}

class AccountSignedOutException : IllegalStateException("No account session")

class AccountSessionExpiredException : IllegalStateException("Account session expired")
