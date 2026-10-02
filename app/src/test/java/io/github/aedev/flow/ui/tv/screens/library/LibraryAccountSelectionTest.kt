package io.github.aedev.flow.ui.tv.screens.library

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibrarySection
import org.junit.Test

class LibraryAccountSelectionTest {
    @Test fun aLateAccountRefreshCannotReplaceTheLocalPaneTheUserOpened() {
        assertThat(selectLibraryAccountSection(true, null, null, "spotify:a")).isNull()
        assertThat(selectLibraryAccountSection(true, null, "spotify:a", "spotify:b")).isNull()
    }

    @Test fun anAccountPaneFollowsItsOwnerAndKeepsItsCurrentSectionForTheSameOwner() {
        assertThat(selectLibraryAccountSection(false, null, null, "spotify:a")).isEqualTo(TvAccountLibrarySection.OVERVIEW)
        assertThat(selectLibraryAccountSection(false, TvAccountLibrarySection.RECENTLY_PLAYED, "spotify:a", "spotify:a"))
            .isEqualTo(TvAccountLibrarySection.RECENTLY_PLAYED)
        assertThat(selectLibraryAccountSection(false, TvAccountLibrarySection.RECENTLY_PLAYED, "spotify:a", "spotify:b"))
            .isEqualTo(TvAccountLibrarySection.OVERVIEW)
    }
}
