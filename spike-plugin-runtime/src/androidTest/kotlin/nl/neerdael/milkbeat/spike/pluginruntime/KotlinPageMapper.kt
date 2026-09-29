package nl.neerdael.milkbeat.spike.pluginruntime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The native baseline: `mapper.js` line for line, over kotlinx.serialization's JSON tree. */
internal object KotlinPageMapper {
    private val shelfKeys = listOf("musicCarouselShelfRenderer", "musicShelfRenderer", "musicPlaylistShelfRenderer", "gridRenderer")

    fun map(text: String): PageSummary {
        val shelves = mutableListOf<Shelf>()
        walk(Json.parseToJsonElement(text), null, shelves)
        return PageSummary.of(shelves)
    }

    private fun walk(
        node: JsonElement,
        shelf: Shelf?,
        shelves: MutableList<Shelf>,
    ) {
        when (node) {
            is JsonArray -> {
                node.forEach { walk(it, shelf, shelves) }
            }

            is JsonObject -> {
                shelfKeys.firstNotNullOfOrNull { node[it] as? JsonObject }?.let { found ->
                    val next = Shelf(shelfTitle(found), mutableListOf())
                    shelves += next
                    (found["contents"] ?: found["items"])?.let { walk(it, next, shelves) }
                    return
                }
                (node["musicTwoRowItemRenderer"] as? JsonObject)?.let {
                    shelf?.items?.add(twoRowItem(it))
                    return
                }
                (node["musicResponsiveListItemRenderer"] as? JsonObject)?.let {
                    shelf?.items?.add(listItem(it))
                    return
                }
                node.values.forEach { walk(it, shelf, shelves) }
            }

            is JsonPrimitive -> {
                Unit
            }
        }
    }

    private fun runsText(value: JsonElement?): String =
        (value as? JsonObject)
            ?.get("runs")
            ?.jsonArray
            ?.joinToString("") {
                it.jsonObject["text"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
            }.orEmpty()

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

    private fun lastThumbnail(renderer: JsonElement?): String? =
        (renderer.obj("musicThumbnailRenderer").obj("thumbnail")?.get("thumbnails") as? JsonArray)
            ?.lastOrNull()
            ?.jsonObject
            ?.get("url")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun endpointId(endpoint: JsonElement?): String? =
        endpoint
            .obj("browseEndpoint")
            ?.get("browseId")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: endpoint
                .obj("watchEndpoint")
                ?.get("videoId")
                ?.jsonPrimitive
                ?.contentOrNull

    private fun twoRowItem(item: JsonObject) =
        Item(
            title = runsText(item["title"]),
            subtitle = runsText(item["subtitle"]),
            id = endpointId(item["navigationEndpoint"]),
        )

    private fun listItem(item: JsonObject): Item {
        val texts =
            (item["flexColumns"] as? JsonArray).orEmpty().map { column ->
                runsText(column.obj("musicResponsiveListItemFlexColumnRenderer")?.get("text"))
            }
        return Item(
            title = texts.firstOrNull().orEmpty(),
            subtitle = texts.drop(1).joinToString(" • "),
            id =
                item
                    .obj("playlistItemData")
                    ?.get("videoId")
                    ?.jsonPrimitive
                    ?.contentOrNull ?: endpointId(item["navigationEndpoint"]),
        )
    }

    private fun shelfTitle(shelf: JsonObject): String {
        val header = shelf["header"]
        header.obj("musicCarouselShelfBasicHeaderRenderer")?.let { return runsText(it["title"]) }
        header.obj("gridHeaderRenderer")?.let { return runsText(it["title"]) }
        return runsText(shelf["title"])
    }

    class Shelf(
        val title: String,
        val items: MutableList<Item>,
    )

    class Item(
        val title: String,
        val subtitle: String,
        val id: String?,
    )
}

/** What each engine's output must agree on: counts plus a checksum over the mapped text. */
internal data class PageSummary(
    val shelves: Int,
    val items: Int,
    val checksum: Long,
) {
    companion object {
        fun of(shelves: List<KotlinPageMapper.Shelf>) =
            PageSummary(
                shelves = shelves.size,
                items = shelves.sumOf { it.items.size },
                checksum = shelves.sumOf { shelf -> shelf.title.length + shelf.items.sumOf { it.checksum() }.toLong() },
            )

        /** The summary of `mapper.js` output, decoded outside the timed section. */
        fun ofJson(json: String): PageSummary {
            val shelves =
                Json.parseToJsonElement(json).jsonObject["shelves"]!!.jsonArray.map { shelf ->
                    val obj = shelf.jsonObject
                    KotlinPageMapper.Shelf(
                        title = obj["title"]!!.jsonPrimitive.content,
                        items =
                            obj["items"]!!
                                .jsonArray
                                .map { item ->
                                    val fields = item.jsonObject
                                    KotlinPageMapper.Item(
                                        title = fields["title"]!!.jsonPrimitive.content,
                                        subtitle = fields["subtitle"]!!.jsonPrimitive.content,
                                        id = fields["id"]?.jsonPrimitive?.contentOrNull,
                                    )
                                }.toMutableList(),
                    )
                }
            return of(shelves)
        }

        private fun KotlinPageMapper.Item.checksum(): Int = title.length + subtitle.length + (id?.length ?: 0)
    }
}
