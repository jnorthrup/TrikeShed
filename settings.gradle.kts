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
// - activejs (expect/actual architecture issues - being migrated to SPI)
// - tiny-btrfs (depends on JVM-only root types in commonMain)
// - miniduck (compilation errors in commonMain)
// - miniduck-memory (depends on miniduck)
// - couch (depends on miniduck and tiny-btrfs)
// - ngsctp (JVM-specific ByteBuffer in commonMain)
// - og1 (multiplatform own build, compilation errors)
// - window-toolkit (multiplatform own build, compilation errors)
// - ipfs (uses macro but case commented out)
// - openapi (multiplatform, depends on root for native targets)
// - uring (LiburingFacadeProvider compilation errors)
// - polyglot (GraalPointcutHarness compilation errors)
// - htx-client (references excluded ipfs in source code)
// - cmc-generated (depends on excluded htx-client)
// - motion-estimation (test compilation errors)
// - server (depends on excluded htx-client)
val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    libsDir.listFiles()!!
        .filter { it.isDirectory }
        .filter { it.name !in setOf("classfile", "miniduck-memory", "jvm-agent", "forge", "activejs", "tiny-btrfs", "miniduck", "couch", "ngsctp", "og1", "window-toolkit", "ipfs", "openapi", "uring", "htx-client", "cmc-generated", "motion-estimation", "server") }
        .forEach { include(":libs:${it.name}") }
}

// Include lcnc
include(":libs:lcnc")