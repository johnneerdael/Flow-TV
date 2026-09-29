import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Phase 0 of docs/plugin-architecture.md: measures the plugin script runtime on real TV boxes.
// Nothing here ships; the module only holds an instrumented test.
plugins {
    id("com.android.library")
}

android {
    namespace = "nl.neerdael.milkbeat.spike.pluginruntime"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The recorded YouTube responses are shared with the app's unit tests. YouTube's player script
    // and the solver release are downloaded into build/ by the spike script and never committed.
    sourceSets {
        getByName("androidTest").assets.directories.addAll(
            listOf(
                "$projectDir/src/androidTest/js",
                "$rootDir/app/src/test/resources/catalog",
                "$projectDir/build/spike-assets",
            ),
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    androidTestImplementation(libs.quickjs.kt)
    androidTestImplementation(libs.rhino)
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
