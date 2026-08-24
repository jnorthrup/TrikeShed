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

// libs/ is forbidden. All code lives in src/ source sets. The historical
// libs/ tree was deleted at 7afd1e055, deep-cleaned at e9d65f870, and purged
// at eef1c40e4. Do not recreate it; restore historical code via
// `git show <commit>:<path>` into the matching src/ source set.

// Support hybrid kotlin xvm build if ../xvm exists
if (java.io.File("../xvm").exists()) {
    includeBuild("../xvm")
}
