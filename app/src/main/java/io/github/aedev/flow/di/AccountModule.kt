package io.github.aedev.flow.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.account.AccountSessionStore
import io.github.aedev.flow.data.account.KeystoreSecretSealer
import io.github.aedev.flow.data.account.accountSessionDataStore
import io.github.aedev.flow.data.account.signin.PhoneChannel
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhoneInputServer
import io.github.aedev.flow.data.account.signin.PhoneStatus
import io.github.aedev.flow.sync.transport.LanAddress
import io.github.aedev.flow.ui.screens.account.LanAddressProvider
import io.github.aedev.flow.ui.screens.account.PhoneServerHandle
import io.github.aedev.flow.ui.screens.account.PhoneServerLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccountModule {
    @Provides
    @Singleton
    fun provideAccountSessionStore(
        @ApplicationContext context: Context,
    ): AccountSessionStore = AccountSessionStore(context.accountSessionDataStore, KeystoreSecretSealer)

    @Provides
    fun provideLanAddressProvider(): LanAddressProvider = LanAddressProvider { LanAddress.resolve() }

    @Provides
    fun providePhoneServerLauncher(
        @ApplicationContext context: Context,
    ): PhoneServerLauncher =
        object : PhoneServerLauncher {
            override suspend fun launch(
                channel: PhoneChannel,
                host: String,
                status: () -> PhoneStatus,
                onInput: suspend (PhoneInput) -> Unit,
            ): PhoneServerHandle {
                val server = PhoneInputServer(channel, { path -> context.assets.open(path).use { it.readBytes() } }, status, onInput)
                val boundPort = withContext(Dispatchers.IO) { server.start(host) }
                return object : PhoneServerHandle {
                    override val port = boundPort

                    override fun stop() = server.stop()
                }
            }
        }
}
