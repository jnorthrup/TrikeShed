pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

// Dynamically include every library under libs/ so each subproject can be built autonomously
// Note: miniduck, couch, miniduck-memory, ng-sctp, classfile, ipfs excluded due to build errors
val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    libsDir.listFiles()!!
        .filter { it.isDirectory }
        .filter { it.name !in setOf("ng-sctp", "classfile", "miniduck-memory", "miniduck", "couch", "ipfs") }
        .forEach { include(":libs:${it.name}") }
}

// Include lib_cursor explicitly (skip classfile root and its problematic subprojects)
include(":libs:classfile:lib_cursor")

// Support hybrid kotlin xvm build if ../xvm exists
if (java.io.File("../xvm").exists()) {
    includeBuild("../xvm")
}
include(":libs:lcnc")
