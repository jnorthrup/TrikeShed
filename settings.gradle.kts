pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        // Ensure included builds can resolve the Kotlin Multiplatform plugin
        id("org.jetbrains.kotlin.multiplatform") version "2.4.0-Beta1"
    }
}

rootProject.name = "TrikeShed"
