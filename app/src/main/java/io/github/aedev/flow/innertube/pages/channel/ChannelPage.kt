package io.github.aedev.flow.innertube.pages.channel

/** [tabs] is whatever the channel published, in its order — not a fixed set the screen assumes. */
data class ChannelPage(
    val header: ChannelHeader,
    val tabs: List<ChannelTabDescriptor>,
    val initialTab: ChannelTabContent?,
)

data class ChannelHeader(
    val id: String,
    val title: String,
    val handle: String? = null,
    val avatarUrl: String = "",
    val bannerUrl: String? = null,
    /** YouTube's own localised text; [subscriberCount] re-parses it only for rows that store a number. */
    val subscriberCountText: String? = null,
    val subscriberCount: Long? = null,
    val videoCountText: String? = null,
    val description: String? = null,
    val links: List<ChannelLink> = emptyList(),
    val isVerified: Boolean = false,
    val joinedDateText: String? = null,
    val viewCountText: String? = null,
    val countryText: String? = null,
    val canonicalUrl: String? = null,
)

data class ChannelLink(
    val title: String,
    val displayText: String,
    val url: String,
    val iconUrl: String? = null,
)

/** [params] comes from the response, so a token rotation cannot strand a tab. */
data class ChannelTabDescriptor(
    val kind: ChannelTabKind,
    val title: String,
    val params: String?,
    val selected: Boolean = false,
)

enum class ChannelTabKind {
    Home,
    Videos,
    Shorts,
    Live,
    Shows,
    Podcasts,
    Playlists,
    Posts,
    Releases,
    Store,
    Search,
    Unknown,
}
