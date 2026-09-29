package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertWithMessage
import io.github.smiley4.schemakenerator.jsonschema.JsonSchemaSteps.compileReferencingRoot
import io.github.smiley4.schemakenerator.jsonschema.JsonSchemaSteps.generateJsonSchema
import io.github.smiley4.schemakenerator.jsonschema.JsonSchemaSteps.withTitle
import io.github.smiley4.schemakenerator.jsonschema.data.TitleType
import io.github.smiley4.schemakenerator.serialization.SerializationSteps
import io.github.smiley4.schemakenerator.serialization.SerializationSteps.analyzeTypeUsingKotlinxSerialization
import io.github.smiley4.schemakenerator.serialization.SerializationSteps.convertToKotlinxSerializationTypes
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import java.io.File

/**
 * The JSON Schema of plugin API v1, generated from the Kotlin model. The SDK's TypeScript types are
 * generated from the checked-in copy, so this test failing means the SDK no longer matches the host.
 */
class PluginSchemaTest {
    private val pretty = Json { prettyPrint = true }

    @Test
    fun `the checked-in schema matches the Kotlin model`() {
        val schema = pretty.encodeToString(JsonElement.serializer(), pluginApiSchema()) + "\n"
        val file = File(System.getProperty("pluginSchemaFile"))
        if (System.getProperty("updatePluginSchema").toBoolean()) {
            file.parentFile.mkdirs()
            file.writeText(schema)
            return
        }
        assertWithMessage("${file.path} is stale: run ./gradlew :plugin-api:test -PupdatePluginSchema")
            .that(file.takeIf { it.exists() }?.readText())
            .isEqualTo(schema)
    }

    private fun pluginApiSchema(): JsonObject {
        val definitions = sortedMapOf<String, JsonElement>()

        fun reference(serializer: KSerializer<*>): JsonElement? {
            if (serializer.descriptor == Unit.serializer().descriptor) return null
            val compiled =
                SerializationSteps
                    .initial(serializer.descriptor)
                    .analyzeTypeUsingKotlinxSerialization()
                    .generateJsonSchema()
                    .withTitle(TitleType.SIMPLE)
                    .compileReferencingRoot()
            val converted = withoutPrimitiveTitles(compiled.convertToKotlinxSerializationTypes()).jsonObject
            converted["\$defs"]?.jsonObject?.forEach { (name, definition) -> definitions[name] = definition }
            return JsonObject(converted - "\$defs")
        }

        val manifest = reference(PluginManifest.serializer())!!

        fun calls(operations: List<Triple<String, KSerializer<*>, KSerializer<*>>>) =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    operations.forEach { (path, requestSerializer, responseSerializer) ->
                        val request = reference(requestSerializer)
                        val response = reference(responseSerializer)
                        putJsonObject(path) {
                            put("type", "object")
                            putJsonObject("properties") {
                                request?.let { put("request", it) }
                                response?.let { put("response", it) }
                            }
                            putJsonArray("required") {
                                request?.let { add(JsonPrimitive("request")) }
                                response?.let { add(JsonPrimitive("response")) }
                            }
                            put("additionalProperties", false)
                        }
                    }
                }
                putJsonArray("required") { operations.forEach { add(JsonPrimitive(it.first)) } }
                put("additionalProperties", false)
            }
        val operations = calls(PluginOperations.all.map { Triple(it.path, it.request, it.response) })
        val host = calls(HostOperations.all.map { Triple(it.path, it.request, it.response) })
        (
            PluginOperations.all.flatMap { listOf(it.request.descriptor, it.response.descriptor) } +
                HostOperations.all.flatMap { listOf(it.request.descriptor, it.response.descriptor) } +
                PluginManifest.serializer().descriptor
        ).flatMap(::sealedVariants)
            .distinctBy { it.serialName }
            .forEach { variant ->
                val definition = definitions[variant.serialName]?.jsonObject ?: return@forEach
                definitions[variant.serialName] = withDiscriminator(definition, variant)
            }
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("title", "MilkbeatPluginApi")
            put("description", "Plugin API v$PLUGIN_API_VERSION, generated from the plugin-api module. Do not edit.")
            put("type", "object")
            putJsonObject("properties") {
                put("manifest", manifest)
                put("operations", operations)
                put("host", host)
                put("error", reference(PluginError.serializer())!!)
            }
            putJsonArray("required") {
                add(JsonPrimitive("manifest"))
                add(JsonPrimitive("operations"))
                add(JsonPrimitive("host"))
                add(JsonPrimitive("error"))
            }
            put("\$defs", JsonObject(definitions))
        }
    }

    /** A variant of a sealed type: kotlinx writes it with `"type": "<serialName>"`. */
    private class SealedVariant(
        val serialName: String,
        val title: String,
    )

    @OptIn(ExperimentalSerializationApi::class)
    private fun sealedVariants(root: SerialDescriptor): List<SealedVariant> {
        val seen = mutableSetOf<String>()
        val variants = mutableListOf<SealedVariant>()

        fun visit(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor.serialName)) return
            if (descriptor.kind == PolymorphicKind.SEALED) {
                val parent = descriptor.serialName.substringAfterLast('.')
                descriptor.getElementDescriptor(1).elementDescriptors.forEach { variant ->
                    variants += SealedVariant(variant.serialName, parent + variant.serialName.replaceFirstChar(Char::uppercase))
                    visit(variant)
                }
            }
            descriptor.elementDescriptors.forEach(::visit)
        }
        visit(root)
        return variants
    }

    private fun withDiscriminator(
        definition: JsonObject,
        variant: SealedVariant,
    ): JsonObject {
        val properties = definition["properties"]?.jsonObject.orEmpty()
        val required = definition["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        return JsonObject(
            definition +
                mapOf(
                    "title" to JsonPrimitive(variant.title),
                    "properties" to JsonObject(mapOf("type" to buildJsonObject { put("const", variant.serialName) }) + properties),
                    "required" to JsonArray((listOf("type") + required).distinct().map(::JsonPrimitive)),
                ),
        )
    }

    /**
     * Titles become TypeScript type names, so only objects, enums and unions keep theirs; a string
     * or a list titled "String" or "ArrayList" would otherwise turn into dozens of aliases.
     */
    private fun withoutPrimitiveTitles(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                val named =
                    element["type"]?.let { it is JsonPrimitive && it.content == "object" } == true ||
                        "enum" in element ||
                        "anyOf" in element
                JsonObject(
                    element
                        .filterKeys { key -> key != "title" || named }
                        .mapValues { (_, value) -> withoutPrimitiveTitles(value) },
                )
            }

            is JsonArray -> {
                JsonArray(element.map(::withoutPrimitiveTitles))
            }

            else -> {
                element
            }
        }
}
