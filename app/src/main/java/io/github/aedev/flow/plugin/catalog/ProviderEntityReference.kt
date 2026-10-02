package io.github.aedev.flow.plugin.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.plugin.PluginJson
import java.util.Base64

@Serializable
internal data class ProviderEntityReference(
    val pluginId: String,
    val entity: EntityRef,
) {
    companion object {
        private const val PREFIX = "milkbeat-catalog:"

        fun encode(
            pluginId: String,
            entity: EntityRef,
        ): String =
            PREFIX +
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    PluginJson.encodeToString(ProviderEntityReference(pluginId, entity)).toByteArray(Charsets.UTF_8),
                )

        fun decode(value: String): ProviderEntityReference? =
            if (!value.startsWith(PREFIX)) {
                null
            } else {
                runCatching {
                    PluginJson.decodeFromString<ProviderEntityReference>(
                        String(Base64.getUrlDecoder().decode(value.removePrefix(PREFIX)), Charsets.UTF_8),
                    )
                }.getOrNull()
            }
    }
}
