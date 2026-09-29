import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The contract between Milkbeat and its plugins: the manifest, every request and response, and the
// page vocabulary the TV renders. Plain Kotlin, so the JSON Schema and the SDK's TypeScript types
// are generated from these classes and never drift from them.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.schema.kenerator.core)
    testImplementation(libs.schema.kenerator.serialization)
    testImplementation(libs.schema.kenerator.jsonschema)
}

tasks.test {
    // `./gradlew :plugin-api:test -PupdatePluginSchema` rewrites the checked-in schema instead of comparing.
    systemProperty("updatePluginSchema", project.hasProperty("updatePluginSchema"))
    systemProperty("pluginSchemaFile", rootProject.file("plugins/sdk/generated/plugin-api.schema.json").path)
}
