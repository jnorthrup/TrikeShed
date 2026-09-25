pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    plugins {
        kotlin("plugin.compose") version "2.4.20"
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

// libs/ is buried: tracked, never included in this build. All built code
// lives in src/ source sets. A lib is drawn out into the matching src/ source
// set when a use arrives, not before; its buried copy is not a design to
// reconcile with src/.

// Support hybrid kotlin xvm build if ../xvm exists
if (java.io.File("../xvm").exists()) {
    includeBuild("../xvm")
}
