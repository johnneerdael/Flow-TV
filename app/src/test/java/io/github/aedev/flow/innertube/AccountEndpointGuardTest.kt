package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AccountEndpointGuardTest {
    private val served = mutableListOf<String>()

    private fun client() =
        HttpClient(
            MockEngine { request ->
                served += "${request.url.host}${request.url.encodedPath}"
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            defaultRequest { url(YouTubeClient.API_URL_YOUTUBE_MUSIC) }
            install(AccountEndpointGuard) { policy = AccountEndpointPolicy.FEEDS_ONLY }
        }

    private fun Throwable?.isBlocked() = generateSequence(this) { it.cause }.any { it is AccountEndpointBlockedException }

    @Test
    fun `music browse is allowed`() =
        runTest {
            client().post("browse")
            assertThat(served).containsExactly("music.youtube.com/youtubei/v1/browse")
        }

    @Test
    fun `web browse is allowed`() =
        runTest {
            client().post("https://www.youtube.com/youtubei/v1/browse")
            assertThat(served).containsExactly("www.youtube.com/youtubei/v1/browse")
        }

    @Test
    fun `account menu is allowed`() =
        runTest {
            client().post("account/account_menu")
            assertThat(served).containsExactly("music.youtube.com/youtubei/v1/account/account_menu")
        }

    @Test
    fun `playback and write endpoints are blocked before reaching the network`() =
        runTest {
            val blocked =
                listOf(
                    "player",
                    "next",
                    "like/like",
                    "subscription/subscribe",
                    "browse/edit_playlist",
                    "https://www.youtube.com/youtubei/v1/player",
                    "https://www.youtube.com/youtubei/v1/next",
                )
            blocked.forEach { path ->
                assertThat(runCatching { client().post(path) }.exceptionOrNull().isBlocked()).isTrue()
            }
            assertThat(
                runCatching { client().get("https://s.youtube.com/api/stats/playback?docid=x") }.exceptionOrNull().isBlocked(),
            ).isTrue()
            assertThat(served).isEmpty()
        }

    @Test
    fun `an InnerTube built with a policy refuses player`() =
        runTest {
            val tube = InnerTube(AccountEndpointPolicy.FEEDS_ONLY)
            val error = runCatching { tube.player(YouTubeClient.WEB_REMIX, "dQw4w9WgXcQ", null, null) }.exceptionOrNull()
            assertThat(error.isBlocked()).isTrue()
        }
}
