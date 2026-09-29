package io.github.aedev.flow.ui.tv.screens.channel

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.FilterOption
import org.junit.Test

class TvChannelTabsTest {
    private val tabs =
        listOf(
            FilterOption("videos", "Videos"),
            FilterOption("live", "Live"),
            FilterOption("about", "About"),
        )

    @Test
    fun `the first tab shows until another is picked`() {
        assertThat(TvChannelTabs.selected(tabs, null)?.id).isEqualTo("videos")
        assertThat(TvChannelTabs.selected(tabs, "live")?.id).isEqualTo("live")
    }

    @Test
    fun `a tab the channel does not have falls back to the first`() {
        assertThat(TvChannelTabs.selected(tabs, "playlists")?.id).isEqualTo("videos")
        assertThat(TvChannelTabs.selected(emptyList(), "live")).isNull()
    }

    @Test
    fun `the first tab is the unfiltered page, every other tab sends its filter`() {
        assertThat(TvChannelTabs.requestFilter(tabs, "videos")).isNull()
        assertThat(TvChannelTabs.requestFilter(tabs, null)).isNull()
        assertThat(TvChannelTabs.requestFilter(tabs, "about")).isEqualTo("about")
    }
}
