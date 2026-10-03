package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.playback.PluginTrackMatcher
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportMode
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.catalog.TracksRequest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PlaylistMirrorRunnerTest {
    @Test
    fun `fresh runner reuses persisted destination and matches after checking source`() =
        runTest {
            val initial = Fixture(3)
            val prepared = initial.runner.prepare(initial.key, "Playlist")
            val restarted = Fixture(3)
            restarted.stored =
                PluginJson.decodeFromString(MirrorRecord.serializer(), PluginJson.encodeToString(MirrorRecord.serializer(), prepared))
            val events = mutableListOf<String>()
            coEvery { restarted.host.call("source", PluginOperations.tracks, any()) } answers {
                events += "source"
                TrackList(restarted.tracks, revision = restarted.revision)
            }
            coEvery { restarted.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                assertThat(request.target).isEqualTo(prepared.destination)
                events += request.mode.name
                PrivatePlaylistImportResult(prepared.destination)
            }
            val result = restarted.runner.prepare(restarted.key, "Playlist")
            assertThat(events).containsExactly("source", "REPLACE").inOrder()
            assertThat(result.destination).isEqualTo(prepared.destination)
            assertThat(result.matches).isEqualTo(prepared.matches)
            assertThat(result.ready).isTrue()
            assertThat(restarted.calls).isEmpty()
        }

    @Test
    fun `refresh after restart changes order additions and removals on the saved destination`() =
        runTest {
            val initial = Fixture(3)
            val prepared = initial.runner.prepare(initial.key, "Playlist")
            val restarted = Fixture(4)
            restarted.stored =
                PluginJson.decodeFromString(MirrorRecord.serializer(), PluginJson.encodeToString(MirrorRecord.serializer(), prepared))
            restarted.tracks = listOf(restarted.tracks[2], restarted.tracks[0], restarted.tracks[3], restarted.tracks[0])
            restarted.revision = "r2"
            val imports = mutableListOf<PrivatePlaylistImportRequest>()
            coEvery { restarted.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                imports += request
                assertThat(request.target).isEqualTo(prepared.destination)
                PrivatePlaylistImportResult(prepared.destination)
            }
            val result = restarted.runner.prepare(restarted.key, "Renamed")
            assertThat(result.destination).isEqualTo(prepared.destination)
            assertThat(result.title).isEqualTo("Renamed")
            assertThat(result.ready).isTrue()
            assertThat(
                imports.last().tracks.map {
                    it.providerId
                },
            ).containsExactly("nativetrack2", "nativetrack0", "nativetrack3", "nativetrack0").inOrder()
        }

    @Test
    fun `new playlist availability waits suspend instead of polling immediately`() =
        runTest {
            val f = Fixture(1)
            var waiting = true
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                if (thirdArg<PrivatePlaylistImportRequest>().mode == PrivatePlaylistImportMode.ENSURE && waiting) {
                    waiting = false
                    PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"), "waiting", 1000)
                } else {
                    PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"))
                }
            }
            assertThat(f.runner.prepare(f.key, "Playlist").ready).isTrue()
            assertThat(testScheduler.currentTime).isEqualTo(1000L)
        }

    @Test
    fun `replacement of a partial destination restarts append positions from zero`() =
        runTest {
            val f = Fixture(3)
            f.match = { if (it == f.tracks[1]) error("offline") else it }
            runCatching { f.runner.prepare(f.key, "Playlist") }
            assertThat(f.stored?.nextIndex).isEqualTo(1)
            val positions = mutableListOf<Int>()
            f.match = { it }
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                if (request.mode == PrivatePlaylistImportMode.APPEND) positions += checkNotNull(request.startIndex)
                PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "replacement"))
            }
            assertThat(f.runner.prepare(f.key, "Playlist").ready).isTrue()
            assertThat(positions).containsExactly(0, 1, 2).inOrder()
        }

    @Test
    fun `playback without artwork reuses an already prepared source cover`() =
        runTest {
            val f = Fixture(3)
            val cover = Artwork("https://images.example.com/cover.jpg")
            val first = f.runner.prepare(f.key, "Playlist", artwork = cover)
            val second = f.runner.prepare(f.key, "Playlist")
            assertThat(second.revision).isEqualTo(first.revision)
            assertThat(second.artwork).isEqualTo(cover)
            assertThat(f.calls).hasSize(3)
            coVerify(exactly = 6) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `destination is created before matching and each resolved track is appended in order`() =
        runTest {
            val f = Fixture(3)
            val events = mutableListOf<String>()
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                events += "${request.mode}:${request.startIndex}"
                PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"))
            }
            f.match = {
                events += it.ref.providerId
                it
            }
            f.runner.prepare(f.key, "Playlist")
            assertThat(
                events,
            ).containsExactly("ENSURE:null", "track0", "APPEND:0", "track1", "APPEND:1", "track2", "APPEND:2", "REPLACE:null").inOrder()
        }

    @Test
    fun `whole source is matched sequentially preserving duplicates and resumed progress`() =
        runTest {
            val fixture = Fixture(122)
            fixture.tracks = fixture.tracks.dropLast(1) + fixture.tracks.first()
            val first = fixture.runner.prepare(fixture.key, "Playlist")
            assertThat(first.matches).hasSize(122)
            assertThat(first.matches.last().sourcePosition).isEqualTo(121)
            assertThat(first.ready).isTrue()
            assertThat(fixture.calls).hasSize(122)
            fixture.runner.prepare(fixture.key, "Playlist")
            assertThat(fixture.calls).hasSize(122)
            coVerify(exactly = 125) { fixture.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `temporary failure keeps checkpoint and resumes rather than recording a miss`() =
        runTest {
            val f = Fixture(4)
            var fail = true
            f.match = { track -> if (track == f.tracks[2] && fail) error("offline") else track }
            assertThat(runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull()?.message).isEqualTo("offline")
            assertThat(f.stored?.nextIndex).isEqualTo(2)
            assertThat(f.stored?.missed).isEmpty()
            fail = false
            val result = f.runner.prepare(f.key, "Playlist")
            assertThat(result.ready).isTrue()
            assertThat(f.calls.count { it == "track0" }).isEqualTo(1)
            assertThat(f.calls.count { it == "track2" }).isEqualTo(2)
        }

    @Test
    fun `confirmed misses preserve source occurrence mapping`() =
        runTest {
            val f = Fixture(3)
            f.match = { if (it == f.tracks[1]) null else it }
            val result = f.runner.prepare(f.key, "Playlist")
            assertThat(result.matches.map { it.sourcePosition }).containsExactly(0, 2).inOrder()
            assertThat(result.missed).containsExactly(f.tracks[1])
        }

    @Test
    fun `account changes cannot commit a stale match or write a copy`() =
        runTest {
            val f = Fixture(3)
            f.match = {
                f.accounts.value = f.accounts.value + ("target" to ProviderAccount.SignedIn("other"))
                it
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
            assertThat(failure.reason).isEqualTo(MirrorFailure.ACCOUNT_CHANGED)
            assertThat(f.stored?.nextIndex).isEqualTo(0)
            coVerify(exactly = 0) {
                f.host.call(
                    "target",
                    PluginOperations.importPrivatePlaylist,
                    match {
                        it.mode ==
                            PrivatePlaylistImportMode.REPLACE
                    },
                )
            }
        }

    @Test
    fun `source edit during matching is detected before writing and retried from changed source`() =
        runTest {
            val f = Fixture(3)
            var changed = false
            f.match = { track ->
                if (!changed) {
                    changed = true
                    f.revision = "r2"
                }
                track
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
            assertThat(failure.reason).isEqualTo(MirrorFailure.SOURCE_CHANGED)
            coVerify(exactly = 0) {
                f.host.call(
                    "target",
                    PluginOperations.importPrivatePlaylist,
                    match {
                        it.mode ==
                            PrivatePlaylistImportMode.REPLACE
                    },
                )
            }
            assertThat(f.runner.prepare(f.key, "Playlist").ready).isTrue()
        }

    private class Fixture(
        count: Int,
    ) {
        val key = MirrorKey("source", "a", "target", "b", EntityRef(EntityKind.PLAYLIST, "playlist"))
        val host = mockk<PluginHost>()
        val registry = mockk<PluginRegistry>()
        val accountProvider = mockk<PluginAccounts>()
        val matcher = mockk<PluginTrackMatcher>()
        val accounts =
            MutableStateFlow(
                mapOf<String, ProviderAccount>(
                    "source" to ProviderAccount.SignedIn("a"),
                    "target" to ProviderAccount.SignedIn("b"),
                ),
            )
        var tracks = (0 until count).map { TrackDescriptor(EntityRef(EntityKind.TRACK, "track$it"), "Song $it") }
        var revision = "r1"
        val calls = mutableListOf<String>()
        var match: (TrackDescriptor) -> TrackDescriptor? = {
            it.copy(
                ref = it.ref.copy(providerId = "native" + it.ref.providerId),
                ids =
                    mapOf("target" to "native" + it.ref.providerId),
            )
        }
        var stored: MirrorRecord? = null
        val storage =
            object : MirrorStorage {
                override suspend fun get(id: String) = stored

                override suspend fun put(record: MirrorRecord) {
                    stored = record
                }
            }
        val runner = PlaylistMirrorRunner(host, registry, accountProvider, matcher, storage)

        init {
            val plugins =
                listOf("source", "target").map { id ->
                    InstalledPlugin(
                        PluginManifest(
                            1,
                            ApiRange(1, 2),
                            id,
                            id,
                            "1",
                            1,
                            roles =
                                Roles(
                                    metadata =
                                        MetadataRole(
                                            emptySet(),
                                            emptySet(),
                                            id,
                                            personalCollections = id == "source",
                                            privatePlaylistImport =
                                                id == "target",
                                        ),
                                    audio = AudioRole(setOf(id), match = true),
                                ),
                        ),
                        "signer",
                        "test://",
                        0,
                        emptyList(),
                        emptyList(),
                    )
                }
            every { registry.state } returns MutableStateFlow(PluginRegistryState(plugins))
            every { accountProvider.accounts } returns accounts
            coEvery { host.call("source", PluginOperations.tracks, any()) } answers {
                if (thirdArg<TracksRequest>().cursor ==
                    null
                ) {
                    TrackList(tracks.take(60), next = "more".takeIf { tracks.size > 60 }, revision = revision)
                } else {
                    TrackList(tracks.drop(60), revision = revision)
                }
            }
            coEvery { matcher.matchForIndexing(any(), "target", any()) } coAnswers {
                firstArg<TrackDescriptor>().let {
                    calls += it.ref.providerId
                    match(it)
                }
            }
            coEvery { host.call("target", PluginOperations.importPrivatePlaylist, any()) } returns
                PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"))
        }
    }
}
