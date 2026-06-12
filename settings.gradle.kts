pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

// Dynamically include every library under libs/ so each subproject can be built autonomously
// Note: the following are excluded due to:
// - classfile (nested gradle, multiplatform with JVM-only subprojects)
// - miniduck-memory (depends on classfile/miniduck)
// - jvm-agent (standalone java agent)
// - forge (has build issues)
val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    libsDir.listFiles()!!
        .filter { it.isDirectory }
        .filter { it.name !in setOf("classfile", "miniduck-memory", "jvm-agent", "forge") }
        .forEach { include(":libs:${it.name}") }
}

// Include lib_cursor explicitly (skip classfile root and its problematic subprojects)
include(":libs:classfile:lib_cursor")