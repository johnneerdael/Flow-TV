package io.github.aedev.flow.plugin.catalog

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.PageRequest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class ProviderCatalogScopeTest {
    @Test fun playlistRoutingAndContinuationStayWithTheirOrigin() =
        runBlocking {
            val host = mockk<PluginHost>()
            val registry = mockk<PluginRegistry>(relaxed = true)
            val accounts = mockk<PluginAccounts>()
            every { accounts.accounts } returns MutableStateFlow(mapOf("youtube" to ProviderAccount.Anonymous))
            val page = MetadataPage("page", emptyList())
            val ref = EntityRef(EntityKind.PLAYLIST, "PL1")
            coEvery { host.call("youtube", PluginOperations.entity, PageRequest(ref, cursor = "next")) } returns page
            val provider = PluginMetadataProvider(host, registry, accounts).scoped("youtube")
            assertThat(provider.page(ref, "next").getOrThrow()).isEqualTo(page)
            assertThat(provider.id).isEqualTo("youtube")
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.entity, PageRequest(ref, cursor = "next")) }
        }

    @Test fun aProviderRadioSeedSurvivesUnicodeAndRoundTripsWithoutChangingRawIds() {
        val ref = EntityRef(EntityKind.PLAYLIST, "spotify:playlist:é/#1")
        val encoded = ProviderEntityReference.encode("spotify", ref)
        val decoded = ProviderEntityReference.decode(encoded)!!
        assertThat(decoded.pluginId).isEqualTo("spotify")
        assertThat(decoded.entity).isEqualTo(ref)
        assertThat(ProviderEntityReference.decode("PL1")).isNull()
    }

    @Test fun disablingOrRemovingAnOriginHidesItsRetainedAccount() =
        runBlocking {
            val host = mockk<PluginHost>()
            val registry = mockk<PluginRegistry>()
            val accounts = mockk<PluginAccounts>()
            val plugin =
                InstalledPlugin(
                    PluginManifest(
                        1,
                        ApiRange(1, 2),
                        "source",
                        "Source",
                        "1",
                        1,
                        roles = Roles(metadata = MetadataRole(setOf(MetadataSurface.ENTITY), setOf(EntityKind.PLAYLIST), "source")),
                    ),
                    "signer",
                    "test://source",
                    0,
                    emptyList(),
                    emptyList(),
                )
            val installed = MutableStateFlow(PluginRegistryState(listOf(plugin)))
            every { registry.state } returns installed
            every { accounts.accounts } returns MutableStateFlow(mapOf("source" to ProviderAccount.SignedIn("private")))
            val scoped = PluginMetadataProvider(host, registry, accounts).scoped("source")
            assertThat(scoped.account.first()).isEqualTo(ProviderAccount.SignedIn("private"))
            installed.value = PluginRegistryState(listOf(plugin.copy(enabled = false)))
            assertThat(scoped.account.first()).isEqualTo(ProviderAccount.Anonymous)
            installed.value = PluginRegistryState()
            assertThat(scoped.account.first()).isEqualTo(ProviderAccount.Anonymous)
        }
}
