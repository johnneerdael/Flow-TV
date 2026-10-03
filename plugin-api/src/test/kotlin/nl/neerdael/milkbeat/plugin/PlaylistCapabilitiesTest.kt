package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.TrackList
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class PlaylistCapabilitiesTest {
    @Test fun `radio contract exposes optional tuning`() {
        assertThat(RadioRequest.serializer().descriptor.elementNames).contains("filterId")
        assertThat(TrackList.serializer().descriptor.elementNames).containsAtLeast("filters", "selectedFilterId")
    }

    @Test fun `playlist capabilities expose typed private import and owned export`() {
        assertThat(PluginOperations.all.map { it.path })
            .containsAtLeast("metadata.personalCollections", "metadata.importPrivatePlaylist")
        assertThat(MetadataRole.serializer().descriptor.elementNames)
            .containsAtLeast("personalCollections", "privatePlaylistImport")
    }

    @Test fun `old payloads keep native copying and tuning disabled`() {
        val role = PluginJson.decodeFromString(MetadataRole.serializer(), """{"surfaces":[],"entities":[],"idSpace":"legacy"}""")
        assertThat(role.personalCollections).isFalse()
        assertThat(role.privatePlaylistImport).isFalse()
        val request = PluginJson.decodeFromString(RadioRequest.serializer(), """{"seed":{"kind":"TRACK","providerId":"song"}}""")
        assertThat(request.filterId).isNull()
        val list = PluginJson.decodeFromString(TrackList.serializer(), """{"tracks":[],"newOptionalField":true}""")
        assertThat(list.filters).isNull()
        assertThat(list.selectedFilterId).isNull()
    }

    @Test fun `tuning and private imports retain opaque ids and account binding`() {
        val list = TrackList(emptyList(), filters = FilterControl(listOf(FilterOption("opaque", "Familiar"))), selectedFilterId = "opaque")
        assertThat(
            PluginJson.decodeFromString(TrackList.serializer(), PluginJson.encodeToString(TrackList.serializer(), list)),
        ).isEqualTo(list)
        val request =
            PrivatePlaylistImportRequest(
                "source",
                "Playlist",
                listOf(EntityRef(EntityKind.TRACK, "native")),
                expectedAccountKey = "account",
            )
        assertThat(
            PluginJson.decodeFromString(
                PrivatePlaylistImportRequest.serializer(),
                PluginJson.encodeToString(PrivatePlaylistImportRequest.serializer(), request),
            ),
        ).isEqualTo(request)
    }
}
