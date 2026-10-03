package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException

class MirrorArtworkTest {
    private val source =
        mockk<InstalledPlugin>().also {
            every { it.grantedNetwork } returns listOf("images.example.com")
            every { it.id } returns "source"
        }

    @Test
    fun `cover connection failure remains retryable`() =
        runTest {
            val client = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
            val failure =
                runCatching { MirrorArtwork(client).fetch(source, Artwork("https://images.example.com/cover.jpg")) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            assertThat((failure as PluginCallException).error.code).isEqualTo(PluginErrorCode.NETWORK)
        }

    @Test
    fun `temporary CDN failure remains retryable`() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        Response
                            .Builder()
                            .request(it.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("Unavailable")
                            .body("".toResponseBody())
                            .build()
                    }.build()
            val failure =
                runCatching { MirrorArtwork(client).fetch(source, Artwork("https://images.example.com/cover.jpg")) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            assertThat((failure as PluginCallException).error.code).isEqualTo(PluginErrorCode.UNAVAILABLE)
        }
}
