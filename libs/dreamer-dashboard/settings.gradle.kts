pluginManagement {
    repositories {
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
        google()
    }
}

rootProject.name = "dreamer-dashboard"

includeBuild("../../") {
    dependencySubstitution {
        substitute(module("org.bereft:trikeshed")).using(project(":"))
        substitute(module("borg.trikeshed:common")).using(project(":libs:common"))
        substitute(module("borg.trikeshed:couch")).using(project(":libs:couch"))
        substitute(module("borg.trikeshed:miniduck")).using(project(":libs:miniduck"))
    }
}
