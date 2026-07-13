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

rootProject.name = "TrikeShed"

include(":libs:motion-estimation")
include(":libs:gguf")
include(":libs:mlx")
include(":libs:miniduck")
include(":libs:couchdb")
