package io.github.aedev.flow.plugin.install

import android.app.Application
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PluginLinksTest {
    @Test
    fun `a code link preserves leading zeros and is consumed once`() {
        val links = PluginLinks()
        assertThat(links.offer(Uri.parse("milkbeat://add-plugin?url=007"))).isTrue()
        assertThat(links.pending.value).isEqualTo("007")
        links.consume()
        assertThat(links.pending.value).isNull()
    }

    @Test
    fun `numeric input with the wrong length does not become an IP address`() {
        val links = PluginLinks()
        assertThat(links.offer(Uri.parse("milkbeat://add-plugin?url=42"))).isFalse()
        assertThat(links.pending.value).isNull()
    }
}
