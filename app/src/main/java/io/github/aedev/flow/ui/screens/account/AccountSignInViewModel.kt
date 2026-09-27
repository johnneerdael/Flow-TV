package io.github.aedev.flow.ui.screens.account

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountFeedClient
import io.github.aedev.flow.data.account.AccountSession
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.data.account.signin.PhoneChannel
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhoneStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

fun interface LanAddressProvider {
    fun resolve(): String?
}

interface PhoneServerHandle {
    val port: Int

    fun stop()
}

interface PhoneServerLauncher {
    suspend fun launch(
        channel: PhoneChannel,
        host: String,
        status: () -> PhoneStatus,
        onInput: suspend (PhoneInput) -> Unit,
    ): PhoneServerHandle
}

sealed interface AccountSignInState {
    data object Starting : AccountSignInState

    data class Ready(
        val phoneUrl: String,
    ) : AccountSignInState

    data object NoNetwork : AccountSignInState

    data object Unsupported : AccountSignInState

    data object TimedOut : AccountSignInState

    data class SignedIn(
        val accountName: String?,
    ) : AccountSignInState
}

@HiltViewModel
class AccountSignInViewModel
    @Inject
    constructor(
        private val store: AccountSessionStore,
        private val feeds: AccountFeedClient,
        private val lan: LanAddressProvider,
        private val launcher: PhoneServerLauncher,
    ) : ViewModel() {
        private val _state = MutableStateFlow<AccountSignInState>(AccountSignInState.Starting)
        val state: StateFlow<AccountSignInState> = _state.asStateFlow()

        private val inputChannel = Channel<PhoneInput>(Channel.BUFFERED)
        val inputs: Flow<PhoneInput> = inputChannel.receiveAsFlow()

        private var channel: PhoneChannel? = null
        private var server: PhoneServerHandle? = null
        private var timeoutJob: Job? = null

        @Volatile private var step = ""

        @Volatile private var done = false

        @Volatile private var actions = emptyList<String>()

        fun start(loginSupported: Boolean) {
            if (_state.value != AccountSignInState.Starting || channel != null) return
            if (!loginSupported) {
                _state.value = AccountSignInState.Unsupported
                return
            }
            val host = lan.resolve()
            if (host == null) {
                _state.value = AccountSignInState.NoNetwork
                return
            }
            val phoneChannel = PhoneChannel.create().also { channel = it }
            viewModelScope.launch {
                val handle =
                    try {
                        launcher.launch(phoneChannel, host, { PhoneStatus(step, done, actions) }) { inputChannel.send(it) }
                    } catch (e: IOException) {
                        Log.w(TAG, "Phone sign-in server could not start", e)
                        stopServer()
                        _state.value = AccountSignInState.NoNetwork
                        return@launch
                    }
                server = handle
                _state.value = AccountSignInState.Ready("http://$host:${handle.port}/#${phoneChannel.fragment}")
                timeoutJob =
                    launch {
                        delay(SIGN_IN_TIMEOUT_MS)
                        _state.value = AccountSignInState.TimedOut
                        stopServer()
                    }
            }
        }

        fun onPageTitle(title: String) {
            step = title
        }

        fun onPageActions(labels: List<String>) {
            actions = labels
        }

        fun onSessionCaptured(session: AccountSession) {
            viewModelScope.launch {
                done = true
                timeoutJob?.cancel()
                store.save(session)
                val name = feeds.accountInfo().getOrNull()?.name
                if (name != null) store.save(session.copy(accountName = name))
                delay(STATUS_GRACE_MS)
                stopServer()
                _state.value = AccountSignInState.SignedIn(name)
            }
        }

        fun retry() {
            stopServer()
            done = false
            step = ""
            actions = emptyList()
            _state.value = AccountSignInState.Starting
        }

        override fun onCleared() {
            stopServer()
        }

        private fun stopServer() {
            timeoutJob?.cancel()
            timeoutJob = null
            server?.stop()
            server = null
            channel?.destroy()
            channel = null
        }

        companion object {
            private const val TAG = "AccountSignIn"
            const val SIGN_IN_TIMEOUT_MS = 10 * 60_000L
            private const val STATUS_GRACE_MS = 3_000L
        }
    }
