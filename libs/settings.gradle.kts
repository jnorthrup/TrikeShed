pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    plugins {
        kotlin("plugin.compose") version "2.4.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://www.jitpack.io")
        mavenLocal()
    }
}

rootProject.name = "trikeshed-libs"

// Modules matching the working set declared by the parent TrikeShed repo at split time.
// Broken modules (ngsctp, miniduck, uring, ccek-dsl, server, window-toolkit) are excluded.
val workingModules = setOf(
    "couch", "couch:viewserver", "htx-client", "tls", "ipfs", "quic",
    "tiny-btrfs", "kursive", "patl", "concurrency", "dreamer-kmm", "dreamer-dashboard",
    "openapi", "htx-client", "jules-client", "cmc",
    "cmc-generated", "krak", "krak-generated", "rhood-generated", "cpu-cache",
    "ccek-core", "lib", "classfile",
    "polyglot"
)

val brokenModules = setOf(
    "ngsctp", "miniduck", "uring", "ccek-dsl", "server", "window-toolkit"
)

// This repo root IS the old libs/ directory. Include each working submodule's build.gradle.kts.
fileTree(".") {
    include("**/build.gradle.kts")
}.files.map { it.parentFile.name }.distinct()
    .filter { it in workingModules || it == "common" || it == "lib" || it == "classfile" }
    .filter { it !in brokenModules }
    .forEach { include(":$it") }
