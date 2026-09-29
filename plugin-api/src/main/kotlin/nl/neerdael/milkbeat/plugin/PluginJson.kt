package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.json.Json

/**
 * The one JSON dialect between host and plugins. Either side can be newer: unknown fields are
 * ignored, an unknown enum value or a `null` falls back to the field's default, and lists of blocks
 * and items drop what they cannot read. Defaults are written, so plugins see every field; sealed
 * types carry a `type` field.
 */
val PluginJson: Json =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }
