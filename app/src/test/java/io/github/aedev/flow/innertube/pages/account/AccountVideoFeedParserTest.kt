package io.github.aedev.flow.innertube.pages.account

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class AccountVideoFeedParserTest {
    private fun lockup(id: String) =
        """
        { "lockupViewModel": {
          "contentId": "$id", "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
          "contentImage": { "thumbnailViewModel": { "image": { "sources": [ { "url": "https://i.ytimg.test/$id.jpg", "width": 1280, "height": 720 } ] }, "overlays": [] } },
          "metadata": { "lockupMetadataViewModel": { "title": { "content": "Title $id" },
            "metadata": { "contentMetadataViewModel": { "metadataRows": [ { "metadataParts": [ { "text": { "content": "Channel $id" } } ] } ] } } } }
        } }
        """.trimIndent()

    private fun tracking(loggedIn: String) =
        """ "responseContext": { "serviceTrackingParams": [ { "service": "GFEEDBACK", "params": [ { "key": "logged_in", "value": "$loggedIn" } ] } ] } """

    private val continuationItem =
        """{ "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "NEXT" } } } }"""

    private fun parse(raw: String) = Json.parseToJsonElement(raw).toAccountVideoFeed()

    @Test
    fun `home rich grid yields videos, drops ads and keeps the continuation`() {
        val feed =
            parse(
                """
                { ${tracking("1")}, "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": { "selected": true,
                  "content": { "richGridRenderer": { "contents": [
                    { "richItemRenderer": { "content": ${lockup("a1")} } },
                    { "richItemRenderer": { "content": { "adSlotRenderer": { "slotId": "ad" } } } },
                    { "richItemRenderer": { "content": ${lockup("a2")} } },
                    $continuationItem
                  ] } } } } ] } } }
                """.trimIndent(),
            )
        assertThat(feed.videos.map { it.id }).containsExactly("a1", "a2").inOrder()
        assertThat(feed.continuation).isEqualTo("NEXT")
        assertThat(feed.loggedIn).isTrue()
    }

    @Test
    fun `history section list yields videos`() {
        val feed =
            parse(
                """
                { "contents": { "twoColumnBrowseResultsRenderer": { "tabs": [ { "tabRenderer": { "selected": true,
                  "content": { "sectionListRenderer": { "contents": [
                    { "itemSectionRenderer": { "contents": [ ${lockup("h1")}, ${lockup("h2")} ] } },
                    $continuationItem
                  ] } } } } ] } } }
                """.trimIndent(),
            )
        assertThat(feed.videos.map { it.id }).containsExactly("h1", "h2").inOrder()
        assertThat(feed.continuation).isEqualTo("NEXT")
        assertThat(feed.loggedIn).isNull()
    }

    @Test
    fun `a continuation page appends items`() {
        val feed =
            parse(
                """
                { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
                  { "richItemRenderer": { "content": ${lockup("c1")} } }
                ] } } ] }
                """.trimIndent(),
            )
        assertThat(feed.videos.map { it.id }).containsExactly("c1")
        assertThat(feed.continuation).isNull()
    }

    @Test
    fun `logged_in 0 is reported`() {
        assertThat(parse("{ ${tracking("0")} }").loggedIn).isFalse()
    }

    @Test
    fun `duplicates are removed`() {
        val feed =
            parse(
                """
                { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
                  { "richItemRenderer": { "content": ${lockup("d1")} } }, { "richItemRenderer": { "content": ${lockup("d1")} } }
                ] } } ] }
                """.trimIndent(),
            )
        assertThat(feed.videos.map { it.id }).containsExactly("d1")
    }
}
