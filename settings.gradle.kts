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

// Auto-include local libs as composite builds when present.
// This scans the 'libs' directory and includes any subdirectory that appears
// to be a standalone Gradle build (contains settings.gradle(.kts) or build.gradle(.kts)).
// Avoids hardcoding names and makes local libs participate in root Gradle execution.
//
// Warning: ensure local libs do not depend on the root project to avoid cycles.
if (file("libs").exists() && file("libs").isDirectory) {
    file("libs").listFiles()?.forEach { sub ->
        if (sub.isDirectory) {
            val hasBuild = file("${sub.path}/settings.gradle.kts").exists() ||
                           file("${sub.path}/settings.gradle").exists() ||
                           file("${sub.path}/build.gradle.kts").exists() ||
                           file("${sub.path}/build.gradle").exists()
            if (hasBuild) {
                includeBuild("libs/${sub.name}")
                println("Including local libs composite build: ${sub.name}")
            }
        }
    }
}
