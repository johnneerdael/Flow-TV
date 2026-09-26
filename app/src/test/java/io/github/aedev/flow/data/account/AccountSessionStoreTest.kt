package io.github.aedev.flow.data.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AccountSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val session = AccountSession(cookie = "SAPISID=secret-sapisid; SID=abc", visitorData = "Cgt", dataSyncId = "123")

    @After fun tearDown() = scope.cancel()

    @Test
    fun `a saved session is read back`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope)
            store.save(session)
            assertThat(store.current()).isEqualTo(session)
        }

    @Test
    fun `the cookie never reaches disk in clear text`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope)
            store.save(session)
            val onDisk = File(tmp.root, "account_session.preferences_pb").readBytes().toString(Charsets.UTF_8)
            assertThat(onDisk).doesNotContain("secret-sapisid")
        }

    @Test
    fun `mark expired keeps the session but flags it`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope)
            store.save(session)
            store.markExpired(session.cookie)
            assertThat(store.current()?.expired).isTrue()
        }

    @Test
    fun `clear removes the session`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope)
            store.save(session)
            store.clear()
            assertThat(store.current()).isNull()
        }

    @Test
    fun `toString never prints the cookie`() {
        assertThat(session.toString()).doesNotContain("secret-sapisid")
    }

    @Test
    fun `mark expired after sign-out leaves the store empty`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope)
            store.save(session)
            store.clear()
            store.markExpired(session.cookie)
            assertThat(store.current()).isNull()
        }

    @Test
    fun `mark expired ignores a session that was already replaced`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope)
            store.save(session)
            val next = session.copy(cookie = "SAPISID=next")
            store.save(next)
            store.markExpired(session.cookie)
            assertThat(store.current()).isEqualTo(next)
        }
}
