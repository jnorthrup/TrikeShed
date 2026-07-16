pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    plugins {
        kotlin("plugin.compose") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://www.jitpack.io")
        mavenLocal()
    }
}

rootProject.name = "ingest"

// Composite build: this project consumes TrikeShed by source tree, so a local
// TrikeShed checkout is rebuilt on demand without a mavenLocal publish step.
includeBuild("../..")
