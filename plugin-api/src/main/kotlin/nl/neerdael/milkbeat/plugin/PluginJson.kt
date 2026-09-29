package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.json.Json

/**
 * The one JSON dialect between host and plugins. Unknown fields are ignored so either side can be
 * newer; defaults are written so plugins see every field; sealed types carry a `type` field.
 */
val PluginJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }
