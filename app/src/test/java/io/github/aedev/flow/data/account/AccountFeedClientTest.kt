package io.github.aedev.flow.data.account

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.AccountEndpointBlockedException
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.account.AccountVideoFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountFeedClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val session = AccountSession(cookie = "SAPISID=abc; SID=def", visitorData = "Cgt", dataSyncId = "999")

    @After fun tearDown() = scope.cancel()

    @Test
    fun `the tube carries the session and the anonymous YouTube does not`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope).also { it.save(session) }
            val tube = AccountFeedClient(store).tube()!!
            assertThat(tube.cookie).isEqualTo(session.cookie)
            assertThat(tube.dataSyncId).isEqualTo("999")
            assertThat(tube.useLoginForBrowse).isTrue()
            assertThat(YouTube.cookie).isNull()
            assertThat(YouTube.dataSyncId).isNull()
        }

    @Test
    fun `the tube refuses what the account may not do`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope).also { it.save(session) }
            val tube = AccountFeedClient(store).tube()!!
            val error = runCatching { tube.nextForLiveChat("dQw4w9WgXcQ") }.exceptionOrNull()
            assertThat(generateSequence(error) { it.cause }.any { it is AccountEndpointBlockedException }).isTrue()
        }

    @Test
    fun `an expired session yields no tube`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope).also { it.save(session.copy(expired = true)) }
            assertThat(AccountFeedClient(store).tube()).isNull()
        }

    @Test
    fun `a new session builds a new tube`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope).also { it.save(session) }
            val client = AccountFeedClient(store)
            val first = client.tube()
            store.save(session.copy(cookie = "SAPISID=other"))
            assertThat(client.tube()).isNotSameInstanceAs(first)
        }

    @Test
    fun `a logged-out feed marks the session expired`() {
        val error = runCatching { AccountVideoFeed(emptyList(), null, loggedIn = false).requireLoggedIn() }.exceptionOrNull()
        assertThat(error).isInstanceOf(AccountSessionExpiredException::class.java)
        assertThat(AccountVideoFeed(emptyList(), null, loggedIn = null).requireLoggedIn().videos).isEmpty()
    }

    @Test
    fun `the account client keeps its own http cache`() =
        runBlocking {
            val store = testAccountSessionStore(tmp.root, scope).also { it.save(session) }
            assertThat(AccountFeedClient(store).tube()!!.cacheDirectory?.name).isEqualTo("account_http_cache")
        }
}
