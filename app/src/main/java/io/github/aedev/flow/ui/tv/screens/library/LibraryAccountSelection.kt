package io.github.aedev.flow.ui.tv.screens.library

import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibrarySection

internal fun selectLibraryAccountSection(
    localPaneSelected: Boolean,
    selected: TvAccountLibrarySection?,
    previousOwner: String?,
    currentOwner: String,
): TvAccountLibrarySection? =
    when {
        localPaneSelected -> null
        previousOwner != currentOwner || selected == null -> TvAccountLibrarySection.OVERVIEW
        else -> selected
    }
