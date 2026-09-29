package io.github.aedev.flow.ui.tv.screens.account

import nl.neerdael.milkbeat.catalog.ProviderAccount

/** Whose account the music plugin is serving, as the Library and Settings show it. */
sealed interface TvAccountStatus {
    /** No music plugin is chosen, or the chosen one has no accounts. */
    data object Unavailable : TvAccountStatus

    data object SignedOut : TvAccountStatus

    data class SignedIn(
        val name: String?,
        val avatarUrl: String?,
    ) : TvAccountStatus

    /** The plugin said the sign-in no longer works; signing in again fixes it. */
    data object Expired : TvAccountStatus
}

/** [account] is what the plugin last reported, or null when it has not been asked yet. */
internal fun tvAccountStatus(
    hasAccounts: Boolean,
    account: ProviderAccount?,
): TvAccountStatus =
    when {
        !hasAccounts -> TvAccountStatus.Unavailable
        account is ProviderAccount.SignedIn -> TvAccountStatus.SignedIn(account.name, account.avatar?.url)
        account == ProviderAccount.Expired -> TvAccountStatus.Expired
        else -> TvAccountStatus.SignedOut
    }
