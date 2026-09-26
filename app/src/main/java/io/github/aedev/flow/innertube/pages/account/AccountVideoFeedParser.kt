package io.github.aedev.flow.innertube.pages.account

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.innertube.pages.arrayOrNull
import io.github.aedev.flow.innertube.pages.objectOrNull
import io.github.aedev.flow.innertube.pages.renderer.FeedItem
import io.github.aedev.flow.innertube.pages.renderer.FeedItemOwner
import io.github.aedev.flow.innertube.pages.renderer.toFeedItem
import io.github.aedev.flow.innertube.pages.stringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class AccountVideoFeed(
    val videos: List<Video>,
    val continuation: String?,
    val loggedIn: Boolean?,
)

internal fun JsonElement.toAccountVideoFeed(): AccountVideoFeed {
    val root = objectOrNull() ?: return AccountVideoFeed(emptyList(), null, null)
    val entries = root.firstPageEntries() + root.continuationEntries()
    val videos =
        entries
            .mapNotNull { it.toFeedItem(FeedItemOwner()) }
            .filterIsInstance<FeedItem.VideoItem>()
            .map { it.video }
            .distinctBy { it.id }
    val continuation =
        entries.firstNotNullOfOrNull { entry ->
            entry
                .objectOrNull()
                ?.get("continuationItemRenderer")
                .objectOrNull()
                ?.get("continuationEndpoint")
                .objectOrNull()
                ?.get("continuationCommand")
                .objectOrNull()
                ?.get("token")
                .stringOrNull()
        }
    return AccountVideoFeed(videos, continuation, root.loggedIn())
}

private fun JsonObject.firstPageEntries(): List<JsonElement> {
    val tabs =
        this["contents"]
            .objectOrNull()
            ?.get("twoColumnBrowseResultsRenderer")
            .objectOrNull()
            ?.get("tabs")
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { it.objectOrNull()?.get("tabRenderer").objectOrNull() }
    val content =
        (tabs.firstOrNull { it["selected"].stringOrNull() == "true" } ?: tabs.firstOrNull())
            ?.get("content")
            .objectOrNull()
            ?: return emptyList()
    val grid =
        content["richGridRenderer"]
            .objectOrNull()
            ?.get("contents")
            .arrayOrNull()
            .orEmpty()
    val sections =
        content["sectionListRenderer"]
            .objectOrNull()
            ?.get("contents")
            .arrayOrNull()
            .orEmpty()
    return grid + sections.flatMap { it.unwrapItemSection() }
}

private fun JsonObject.continuationEntries(): List<JsonElement> =
    this["onResponseReceivedActions"]
        .arrayOrNull()
        .orEmpty()
        .flatMap { action ->
            val body = action.objectOrNull()
            (body?.get("appendContinuationItemsAction") ?: body?.get("reloadContinuationItemsCommand"))
                .objectOrNull()
                ?.get("continuationItems")
                .arrayOrNull()
                .orEmpty()
                .flatMap { it.unwrapItemSection() }
        }

private fun JsonElement.unwrapItemSection(): List<JsonElement> =
    objectOrNull()
        ?.get("itemSectionRenderer")
        .objectOrNull()
        ?.get("contents")
        .arrayOrNull()
        ?.toList()
        ?: listOf(this)

private fun JsonObject.loggedIn(): Boolean? =
    this["responseContext"]
        .objectOrNull()
        ?.get("serviceTrackingParams")
        .arrayOrNull()
        .orEmpty()
        .flatMap {
            it
                .objectOrNull()
                ?.get("params")
                .arrayOrNull()
                .orEmpty()
        }.firstNotNullOfOrNull { param ->
            val entry = param.objectOrNull() ?: return@firstNotNullOfOrNull null
            if (entry["key"].stringOrNull() == "logged_in") entry["value"].stringOrNull() == "1" else null
        }
