pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
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

