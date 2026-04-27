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

// Auto-include all libs/ subprojects — each must have a build.gradle.kts.
// Exclude standalone composites that manage their own includeBuild references.
val standaloneLibs = setOf("dreamer-kmm", "dreamer-test-runner", "kursive", "openapi", "dreamer-dashboard")

file("libs").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
    if (dir.name !in standaloneLibs && file("libs/${dir.name}/build.gradle.kts").exists()) {
        include(":libs:${dir.name}")
        project(":libs:${dir.name}").projectDir = dir
    }
}

// Integration harness project for end-to-end SQL→MiniDuck validation
include(":integration-scratch")
project(":integration-scratch").projectDir = file("integration-scratch")
