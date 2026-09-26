package io.github.aedev.flow.ui.screens.account

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel

/** One activity-scoped instance, so every TV surface shares the loaded feeds and the sign-out. */
@Composable
fun sharedAccountFeedsViewModel(): AccountFeedsViewModel {
    val activity = LocalContext.current as? ComponentActivity
    return if (activity != null) hiltViewModel(activity) else hiltViewModel()
}
