pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Milkbeat"
include(":app")
include(":catalog-api")
include(":benchmark")
include(":projectm-core")
project(":projectm-core").projectDir = file("third_party/projectm-tv/core")
