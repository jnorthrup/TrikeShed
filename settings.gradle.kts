pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

// Keep root build focused: wire only the Forge app/library surface for now.
// Additional libs remain standalone until their Gradle contracts are made green.
include(":libs:forge")