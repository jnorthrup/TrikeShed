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

// Dynamically include every library under libs/ so each subproject can be built autonomously
// Note: ng-sctp, classfile (nested gradle), miniduck-memory (depends on classfile), ipfs (build errors) excluded
val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    libsDir.listFiles()!!
        .filter { it.isDirectory }
        .filter { it.name != "ng-sctp" && it.name != "classfile" && it.name != "miniduck-memory" && it.name != "ipfs" }
        .forEach { include(":libs:${it.name}") }
}

// Include lib_cursor explicitly (skip classfile root and its problematic subprojects)
include(":libs:classfile:lib_cursor")

// Support hybrid kotlin xvm build if ../xvm exists
 feature/lcnc-grid-algebra-13076933959051589929
if (java.io.File("../xvm").exists()) {
    includeBuild("../xvm")
}
include(":libs:lcnc")
   
