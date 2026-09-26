package io.github.aedev.flow.ui.screens.account

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.account.AccountFeedClient
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.data.account.signin.PhoneChannel
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhoneStatus
import io.github.aedev.flow.innertube.models.AccountInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<AccountSessionStore> { coEvery { save(any()) } just runs }
    private val feeds =
        mockk<AccountFeedClient> {
            coEvery { accountInfo() } returns
                Result.success(AccountInfo("Test User", null, null, null))
        }
    private var launches = 0
    private var stops = 0
    private val launcher =
        object : PhoneServerLauncher {
            override suspend fun launch(
                channel: PhoneChannel,
                host: String,
                status: () -> PhoneStatus,
                onInput: suspend (PhoneInput) -> Unit,
            ): PhoneServerHandle {
                launches++
                return object : PhoneServerHandle {
                    override val port = 4321

                    override fun stop() {
                        stops++
                    }
                }
            }
        }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(lan: String? = "192.168.50.101") = AccountSignInViewModel(store, feeds, { lan }, launcher)

    @Test
    fun `no LAN address shows NoNetwork and starts nothing`() =
        runTest(dispatcher) {
            val vm = viewModel(lan = null)
            vm.start(loginSupported = true)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(AccountSignInState.NoNetwork)
            assertThat(launches).isEqualTo(0)
        }

    @Test
    fun `unsupported WebView shows Unsupported and starts nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = false)
            advanceUntilIdle()
            assertThat(vm.state.value).isEqualTo(AccountSignInState.Unsupported)
            assertThat(launches).isEqualTo(0)
        }

    @Test
    fun `ready exposes a LAN url with the key in the fragment`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            runCurrent()
            val url = (vm.state.value as AccountSignInState.Ready).phoneUrl
            assertThat(url).matches("""http://192\.168\.50\.101:4321/#[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}""")
        }

    @Test
    fun `the server stops after ten minutes`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            advanceTimeBy(AccountSignInViewModel.SIGN_IN_TIMEOUT_MS + 1)
            assertThat(vm.state.value).isEqualTo(AccountSignInState.TimedOut)
            assertThat(stops).isEqualTo(1)
        }

    @Test
    fun `a captured session is saved with the account name and the server stops`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            runCurrent()
            vm.onSessionCaptured(AccountSession(cookie = "SAPISID=x"))
            advanceUntilIdle()
            coVerify { store.save(AccountSession(cookie = "SAPISID=x")) }
            coVerify { store.save(AccountSession(cookie = "SAPISID=x", accountName = "Test User")) }
            assertThat(vm.state.value).isEqualTo(AccountSignInState.SignedIn("Test User"))
            assertThat(stops).isEqualTo(1)
        }

    @Test
    fun `leaving the screen stops the server`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            runCurrent()
            ViewModelStore().apply {
                put("vm", vm)
                clear()
            }
            assertThat(stops).isEqualTo(1)
        }

    @Test
    fun `a server that cannot start shows NoNetwork instead of crashing`() =
        runTest(dispatcher) {
            val failing =
                object : PhoneServerLauncher {
                    override suspend fun launch(
                        channel: PhoneChannel,
                        host: String,
                        status: () -> PhoneStatus,
                        onInput: suspend (PhoneInput) -> Unit,
                    ): PhoneServerHandle = throw java.net.BindException("address in use")
                }
            val vm = AccountSignInViewModel(store, feeds, { "192.168.50.101" }, failing)
            vm.start(loginSupported = true)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(AccountSignInState.NoNetwork)
        }
}
