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
    }
}

rootProject.name = "user-signals"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("org.bereft:TrikeShed")).using(project(":"))
    }
}