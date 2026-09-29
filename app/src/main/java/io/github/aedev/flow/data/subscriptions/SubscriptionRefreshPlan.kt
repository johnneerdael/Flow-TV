package io.github.aedev.flow.data.subscriptions

/**
 * Which channels the next subscription-feed refresh should actually hit.
 *
 * [isFullRefresh] means every subscribed channel is included, so the caller may replace the whole
 * cache instead of splicing the fetched channels back into it.
 */
data class SubscriptionRefreshPlan(
    val channelIds: List<String>,
    val isFullRefresh: Boolean,
) {
    val isEmpty: Boolean get() = channelIds.isEmpty()

    companion object {
        val NOTHING_TO_DO = SubscriptionRefreshPlan(channelIds = emptyList(), isFullRefresh = false)
    }
}
