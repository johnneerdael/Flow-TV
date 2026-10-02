package io.github.aedev.flow.ui.tv.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ObjectInputStream

class LegacyLibraryStateTest {
    @Test fun aPreviousLikesSelectionStillDeserializesAfterUpgrade() {
        val stream = javaClass.getResourceAsStream("/library/legacy-likes-selection.ser")!!
        val restored = ObjectInputStream(stream).use { it.readObject() } as Enum<*>
        assertThat(restored.name).isEqualTo("LIKES")
    }
}
