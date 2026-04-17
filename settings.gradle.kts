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

// Attempt to reconcile with a local libs/duckdb composite build. If a local
// libs/duckdb folder exists, include it so the project can reference a
// local DuckDB build instead of an external JDBC artifact.
// NOTE: composite inclusion causes circular task dependencies with the
// local libs/duckdb module. Prefer adding its sources to root sourceSets
// for local development. To enable composite builds, uncomment the block
// below and ensure libs/duckdb does not depend on the root project.
// if (file("libs/duckdb").exists()) {
//     includeBuild("libs/duckdb")
//     println("Including local libs/duckdb composite build")
// }
