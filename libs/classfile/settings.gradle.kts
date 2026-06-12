pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://www.jitpack.io")
    }
}

rootProject.name = "classfile"

includeBuild("../..") {
    dependencySubstitution {
        substitute(module("org.bereft:TrikeShed")).using(project(":"))
    }
}
