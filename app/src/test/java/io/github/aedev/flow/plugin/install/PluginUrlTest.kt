package io.github.aedev.flow.plugin.install

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginUrlTest {
    @Test
    fun `a domain and path default to HTTPS`() {
        assertThat(pluginUrl("ntsk.app/spot").toString()).isEqualTo("https://ntsk.app/spot")
        assertThat(pluginUrl("  ntsk.app/spot  ").toString()).isEqualTo("https://ntsk.app/spot")
        assertThat(pluginUrl("ntsk.app/spot?version=latest").toString()).isEqualTo("https://ntsk.app/spot?version=latest")
    }

    @Test
    fun `explicit web schemes and ports are preserved`() {
        assertThat(pluginUrl("https://ntsk.app/spot").toString()).isEqualTo("https://ntsk.app/spot")
        assertThat(pluginUrl("http://192.168.50.80:8769/spotify.mbplugin").toString())
            .isEqualTo("http://192.168.50.80:8769/spotify.mbplugin")
    }

    @Test
    fun `invalid or non web links are refused`() {
        listOf(
            "",
            "not a link",
            "ftp://ntsk.app/spot",
            "javascript:alert(1)",
            "https:/ntsk.app/spot",
            "https://",
            "https://user:password@ntsk.app/spot",
        ).forEach { value -> assertThat(pluginUrl(value)).isNull() }
    }
}
