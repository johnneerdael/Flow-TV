package io.github.aedev.flow.innertube

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A policy that allows nothing proves each call went through the supplied InnerTube, offline. */
class YouTubeAccountRoutingTest {
    private val blockEverything = InnerTube(AccountEndpointPolicy(emptySet()))

    private fun Result<*>.wasBlocked() = generateSequence(exceptionOrNull()) { it.cause }.any { it is AccountEndpointBlockedException }

    @Test
    fun `home uses the supplied InnerTube`() = runTest { assertThat(YouTube.home(via = blockEverything).wasBlocked()).isTrue() }

    @Test
    fun `home continuation uses the supplied InnerTube`() =
        runTest { assertThat(YouTube.home(continuation = "token", via = blockEverything).wasBlocked()).isTrue() }

    @Test
    fun `playlist uses the supplied InnerTube`() =
        runTest { assertThat(YouTube.playlist("LM", via = blockEverything).wasBlocked()).isTrue() }

    @Test
    fun `library uses the supplied InnerTube`() =
        runTest { assertThat(YouTube.library("FEmusic_liked_playlists", via = blockEverything).wasBlocked()).isTrue() }

    @Test
    fun `music history uses the supplied InnerTube`() =
        runTest {
            assertThat(YouTube.musicHistory(via = blockEverything).wasBlocked()).isTrue()
        }

    @Test
    fun `account info uses the supplied InnerTube`() =
        runTest { assertThat(YouTube.accountInfo(via = blockEverything).wasBlocked()).isTrue() }

    @Test
    fun `the anonymous YouTube never holds a cookie`() {
        assertThat(YouTube.cookie).isNull()
        assertThat(YouTube.dataSyncId).isNull()
    }
}
