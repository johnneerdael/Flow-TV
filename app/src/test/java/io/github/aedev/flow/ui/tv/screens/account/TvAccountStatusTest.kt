package io.github.aedev.flow.ui.tv.screens.account

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.ProviderAccount
import org.junit.Test

class TvAccountStatusTest {
    @Test
    fun `a plugin without accounts has no account to show, whatever it reports`() {
        assertThat(tvAccountStatus(hasAccounts = false, account = ProviderAccount.SignedIn("k"))).isEqualTo(TvAccountStatus.Unavailable)
    }

    @Test
    fun `a signed-in account shows its name and avatar`() {
        val account = ProviderAccount.SignedIn("k", name = "Listener", avatar = Artwork("https://avatar"))

        assertThat(tvAccountStatus(true, account)).isEqualTo(TvAccountStatus.SignedIn("Listener", "https://avatar"))
    }

    @Test
    fun `an expired sign-in is told apart from never having signed in`() {
        assertThat(tvAccountStatus(true, ProviderAccount.Expired)).isEqualTo(TvAccountStatus.Expired)
        assertThat(tvAccountStatus(true, ProviderAccount.Anonymous)).isEqualTo(TvAccountStatus.SignedOut)
        assertThat(tvAccountStatus(true, null)).isEqualTo(TvAccountStatus.SignedOut)
    }
}
