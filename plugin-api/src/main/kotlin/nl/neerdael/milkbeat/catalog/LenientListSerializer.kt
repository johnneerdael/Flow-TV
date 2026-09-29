package nl.neerdael.milkbeat.catalog

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

/**
 * A list that drops the elements it cannot read instead of failing whole: a block type or an enum
 * value from a newer plugin loses that one block or item, not the page. Written like a plain list.
 */
class LenientListSerializer<T>(
    private val element: KSerializer<T>,
) : KSerializer<List<T>> {
    private val list = ListSerializer(element)

    override val descriptor: SerialDescriptor = list.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<T>,
    ) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val json = decoder as? JsonDecoder ?: return list.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: throw SerializationException("Expected a list")
        return array.mapNotNull { item ->
            try {
                json.json.decodeFromJsonElement(element, item)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
