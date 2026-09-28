package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchHistoryMatchingTest {
    private val history = listOf("the weeknd", "anyma", "weekend mix", "Anyma Sphere", "adele").map { SearchHistoryItem(query = it) }

    @Test
    fun `with nothing typed the most recent come first`() {
        assertThat(history.matching("", 2).map { it.query }).containsExactly("the weeknd", "anyma").inOrder()
    }

    @Test
    fun `prefix matches come before other matches, ignoring case`() {
        assertThat(history.matching("wee", 8).map { it.query }).containsExactly("weekend mix", "the weeknd").inOrder()
        assertThat(history.matching("ANY", 8).map { it.query }).containsExactly("anyma", "Anyma Sphere").inOrder()
    }

    @Test
    fun `the limit caps the matches`() {
        assertThat(history.matching("a", 1)).hasSize(1)
    }
}
