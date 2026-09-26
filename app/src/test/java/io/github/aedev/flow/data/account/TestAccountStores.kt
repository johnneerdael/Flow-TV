package io.github.aedev.flow.data.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import java.io.File

fun reversingSealer(): SecretSealer =
    object : SecretSealer {
        override fun seal(plain: String): String = "sealed:" + plain.reversed()

        override fun open(stored: String?): String = stored?.removePrefix("sealed:")?.reversed().orEmpty()
    }

fun testAccountSessionStore(
    dir: File,
    scope: CoroutineScope,
): AccountSessionStore =
    AccountSessionStore(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "account_session.preferences_pb") }),
        reversingSealer(),
    )
