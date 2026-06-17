pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://www.jitpack.io")
    }
}

rootProject.name = "window-toolkit"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("org.bereft:TrikeShed")).using(project(":"))
    }
}