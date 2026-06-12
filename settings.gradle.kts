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
// - activejs (expect/actual architecture issues - being migrated to SPI)
// - og1 (multiplatform own build, compilation errors)
// - ngsctp (JVM-specific ByteBuffer in commonMain)
val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    libsDir.listFiles()!!
        .filter { it.isDirectory }
        .filter { it.name !in setOf(
                    "classfile", "miniduck-memory", "jvm-agent",
                    "activejs", "og1", "ngsctp", "userspace-ebpf",
                    "userspace"
                ) }
        .forEach { include(":libs:${it.name}") }
}

// Include lcnc and couch viewserver
include(":libs:lcnc")
include(":libs:couch:viewserver")