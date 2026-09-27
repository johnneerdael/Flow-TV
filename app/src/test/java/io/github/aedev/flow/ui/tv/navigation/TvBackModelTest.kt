package io.github.aedev.flow.ui.tv.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvBackModelTest {
    @Test
    fun `detail routes pop regardless of tab, history, or rail focus`() {
        TvDestination.entries.forEach { tab ->
            listOf(true, false).forEach { history ->
                listOf(true, false).forEach { rail ->
                    assertThat(
                        TvBackModel.resolve(
                            isOnDetailRoute = true,
                            hasTabHistory = history,
                            currentTab = tab,
                            railHasFocus = rail,
                        ),
                    ).isEqualTo(TvBackAction.POP_DETAIL)
                }
            }
        }
    }

    @Test
    fun `tab history wins over converging on the start tab`() {
        TvDestination.entries.forEach { tab ->
            assertThat(
                TvBackModel.resolve(
                    isOnDetailRoute = false,
                    hasTabHistory = true,
                    currentTab = tab,
                    railHasFocus = false,
                ),
            ).isEqualTo(TvBackAction.POP_TAB)
        }
    }

    @Test
    fun `other tabs without history converge on the start tab`() {
        TvDestination.entries
            .filterNot { it == TvDestination.start }
            .forEach { tab ->
                listOf(true, false).forEach { rail ->
                    assertThat(
                        TvBackModel.resolve(
                            isOnDetailRoute = false,
                            hasTabHistory = false,
                            currentTab = tab,
                            railHasFocus = rail,
                        ),
                    ).isEqualTo(TvBackAction.GO_START)
                }
            }
    }

    @Test
    fun `the start tab moves focus to the rail before exiting`() {
        assertThat(
            TvBackModel.resolve(
                isOnDetailRoute = false,
                hasTabHistory = false,
                currentTab = TvDestination.start,
                railHasFocus = false,
            ),
        ).isEqualTo(TvBackAction.FOCUS_RAIL)
    }

    @Test
    fun `the start tab with rail focused exits`() {
        assertThat(
            TvBackModel.resolve(
                isOnDetailRoute = false,
                hasTabHistory = false,
                currentTab = TvDestination.start,
                railHasFocus = true,
            ),
        ).isEqualTo(TvBackAction.EXIT)
    }
}
