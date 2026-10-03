package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PersonalCollection
import nl.neerdael.milkbeat.catalog.PersonalCollectionKind
import org.junit.Test

class MirrorCollectionsPreparationTest {
    @Test
    fun `unmatched liked songs do not block the next owned playlist`() =
        runTest {
            val collections =
                listOf("liked", "owned").map {
                    PersonalCollection(EntityRef(EntityKind.PLAYLIST, it), it, PersonalCollectionKind.OWNED_PLAYLIST)
                }
            val visited = mutableListOf<String>()
            prepareMirrorCollections(collections) {
                visited += it.title
                if (it.title == "liked") throw MirrorPreparationException(MirrorFailure.NO_MATCHES)
            }
            assertThat(visited).containsExactly("liked", "owned").inOrder()
        }
}
