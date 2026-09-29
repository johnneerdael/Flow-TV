package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.ProviderAccount
import org.junit.Test

class PluginJsonTest {
    @Test
    fun `a page travels with typed blocks and survives the round trip`() {
        val page =
            MetadataPage(
                id = "home",
                blocks =
                    listOf(
                        CollectionBlock(
                            id = "quick-picks",
                            header = null,
                            layout = CollectionLayout.MULTI_COLUMN_LIST,
                            defaultItemView = ItemView.TRACK_ROW,
                            items = listOf(MetadataItem("a", EntityRef(EntityKind.TRACK, "abc"), "Aria")),
                        ),
                    ),
            )

        val json = PluginJson.encodeToJsonElement(MetadataPage.serializer(), page)

        assertThat(
            json.jsonObject["blocks"]!!
                .jsonArray[0]
                .jsonObject["type"]!!
                .jsonPrimitive.content,
        ).isEqualTo("collection")
        assertThat(PluginJson.decodeFromJsonElement(MetadataPage.serializer(), json)).isEqualTo(page)
    }

    @Test
    fun `a plugin may send fields this host does not know and leave out optional ones`() {
        val account = PluginJson.decodeFromString(ProviderAccount.serializer(), """{"type":"signedIn","key":"k","future":1}""")

        assertThat(account).isEqualTo(ProviderAccount.SignedIn(key = "k"))
    }

    @Test
    fun `a minimal manifest reads with every default filled in`() {
        val manifest =
            PluginJson.decodeFromString(
                PluginManifest.serializer(),
                """
                {"format":1,"api":{"min":1,"target":1},"id":"dev.example.demo","name":"Demo","version":"1.0.0",
                 "versionCode":1,"roles":{"audio":{"idSpaces":["demo"]}}}
                """.trimIndent(),
            )

        assertThat(manifest.entry).isEqualTo("plugin.js")
        assertThat(manifest.roles.audio?.idSpaces).containsExactly("demo")
        assertThat(manifest.permissions.network).isEmpty()
        assertThat(manifest.roles.metadata).isNull()
    }
}
