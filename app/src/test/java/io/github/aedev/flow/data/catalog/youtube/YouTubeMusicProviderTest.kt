package io.github.aedev.flow.data.catalog.youtube

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.account.AccountFeedClient
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.pages.HomePage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import org.junit.After
import org.junit.Before
import org.junit.Test

class YouTubeMusicProviderTest {
    private val sessions = MutableStateFlow<AccountSession?>(null)
    private val store =
        mockk<AccountSessionStore> {
            every { session } returns sessions
            coEvery { current() } coAnswers { sessions.first() }
        }
    private val client = mockk<AccountFeedClient>()
    private val empty = HomePage(chips = null, sections = emptyList())

    private fun provider() = YouTubeMusicProvider(store, client, YouTube)

    @Before
    fun setUp() {
        mockkObject(YouTube)
        coEvery { YouTube.home(any(), any(), any()) } returns Result.success(empty)
        coEvery { client.musicHome(any(), any()) } returns Result.success(empty)
    }

    @After
    fun tearDown() {
        unmockkObject(YouTube)
    }

    @Test
    fun `a signed-in session reads the account's home, not the anonymous one`() =
        runTest {
            sessions.value = AccountSession(cookie = "SAPISID=a")

            provider().home(HomeRequest())

            coVerify(exactly = 1) { client.musicHome(continuation = null, params = null) }
            coVerify(exactly = 0) { YouTube.home(any(), any(), any()) }
        }

    @Test
    fun `signed out or expired, the anonymous home is read`() =
        runTest {
            provider().home(HomeRequest())
            sessions.value = AccountSession(cookie = "SAPISID=a", expired = true)
            provider().home(HomeRequest())

            coVerify(exactly = 2) { YouTube.home(continuation = null, params = null, via = any()) }
            coVerify(exactly = 0) { client.musicHome(any(), any()) }
        }

    @Test
    fun `a filter selects the first page only, continuations already carry it`() =
        runTest {
            provider().home(HomeRequest(filterId = "relax"))
            provider().home(HomeRequest(filterId = "relax", cursor = "c1"))

            coVerify { YouTube.home(continuation = null, params = "relax", via = any()) }
            coVerify { YouTube.home(continuation = "c1", params = null, via = any()) }
        }

    @Test
    fun `a rotated cookie on the same account is not another account`() =
        runTest {
            val provider = provider()
            sessions.value = AccountSession(cookie = "SAPISID=a", dataSyncId = "sync-1")
            val before = provider.account.first()

            sessions.value = AccountSession(cookie = "SAPISID=b", dataSyncId = "sync-1")

            assertThat(provider.account.first()).isEqualTo(before)
        }

    @Test
    fun `the account is named without its credentials`() =
        runTest {
            val provider = provider()
            assertThat(provider.account.first()).isEqualTo(ProviderAccount.Anonymous)

            sessions.value = AccountSession(cookie = "SAPISID=secret", dataSyncId = "sync-1")
            assertThat(provider.account.first()).isEqualTo(ProviderAccount.SignedIn("sync-1"))

            sessions.value = AccountSession(cookie = "SAPISID=secret")
            val key = (provider.account.first() as ProviderAccount.SignedIn).key
            assertThat(key).doesNotContain("secret")

            sessions.value = AccountSession(cookie = "SAPISID=secret", expired = true)
            assertThat(provider.account.first()).isEqualTo(ProviderAccount.Expired)
        }
}
