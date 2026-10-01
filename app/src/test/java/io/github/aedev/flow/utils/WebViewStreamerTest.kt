package io.github.aedev.flow.utils

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class WebViewStreamerTest {
    private class RecordingWebView(
        context: Context,
    ) : WebView(context) {
        var resumes = 0
        var pauses = 0

        override fun onResume() {
            resumes++
        }

        override fun onPause() {
            pauses++
        }
    }

    private lateinit var view: RecordingWebView

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        view = RecordingWebView(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        view.destroy()
        Dispatchers.resetMain()
    }

    @Test
    fun `a hidden streamer captures nothing and accepts no text`() =
        runTest {
            val streamer = WebViewStreamer(view, null)
            streamer.visible(false)
            assertThat(streamer.frame()).isNull()
            assertThat(streamer.acceptsInput).isFalse()
            assertThat(streamer.typeText("fixture")).isFalse()
            assertThat(view.pauses).isEqualTo(1)
        }

    @Test
    fun `a released streamer ignores late lifecycle callbacks`() {
        val streamer = WebViewStreamer(view, null)
        val resource: Any = streamer
        assertThat(resource).isInstanceOf(AutoCloseable::class.java)
        (resource as AutoCloseable).close()
        val pauses = view.pauses
        streamer.visible(true)
        streamer.visible(false)
        assertThat(streamer.visible).isFalse()
        assertThat(view.resumes).isEqualTo(0)
        assertThat(view.pauses).isEqualTo(pauses)
    }
}
