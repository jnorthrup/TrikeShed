pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    plugins {
        kotlin("plugin.compose") version "2.4.0"
        id("org.jetbrains.kotlin.multiplatform") version "2.4.0"
        id("org.jetbrains.kotlin.jvm") version "2.4.0"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
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

include(":keymux")
include(":modelmux")
include(":forge")
include(":lib")