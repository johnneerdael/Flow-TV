package io.github.aedev.flow.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicResponsiveHeaderRenderer(
    val thumbnail: ThumbnailRenderer?,
    val buttons: List<Button> = emptyList(),
    val title: Runs,
    val subtitle: Runs,
    val secondSubtitle: Runs?,
    val straplineTextOne: Runs?,
    val subtitleBadge: List<Badges>? = null,
    val straplineThumbnail: ThumbnailRenderer? = null,
    val description: Description? = null,
    val facepile: Facepile? = null,
) {
    /** A playlist's owner: avatar, name and a link to their page. */
    @Serializable
    data class Facepile(
        val avatarStackViewModel: AvatarStack?,
    ) {
        @Serializable
        data class AvatarStack(
            val avatars: List<Avatar> = emptyList(),
            val text: Text?,
            val rendererContext: RendererContext?,
        )

        @Serializable
        data class Avatar(
            val avatarViewModel: AvatarViewModel?,
        ) {
            @Serializable
            data class AvatarViewModel(
                val image: Image?,
            )

            @Serializable
            data class Image(
                val sources: List<Source> = emptyList(),
            )

            @Serializable
            data class Source(
                val url: String,
            )
        }

        @Serializable
        data class Text(
            val content: String?,
        )

        @Serializable
        data class RendererContext(
            val commandContext: CommandContext?,
        ) {
            @Serializable
            data class CommandContext(
                val onTap: OnTap?,
            )

            @Serializable
            data class OnTap(
                val innertubeCommand: NavigationEndpoint?,
            )
        }
    }

    @Serializable
    data class Description(
        val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer?,
    )

    @Serializable
    data class Button(
        val musicPlayButtonRenderer: MusicPlayButtonRenderer?,
        val menuRenderer: Menu.MenuRenderer?,
    ) {
        @Serializable
        data class MusicPlayButtonRenderer(
            val playNavigationEndpoint: NavigationEndpoint?,
        )
    }
}
