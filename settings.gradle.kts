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
// - user-signals (JVM-specific code in commonMain, @JvmInline issues)
// - ngsctp (JVM-specific ByteBuffer in commonMain)
// - og1 (multiplatform own build, compilation errors)
// - window-toolkit (multiplatform own build, compilation errors)
// - openapi (multiplatform, depends on root for native targets)
// - ipfs (multiplatform, depends on root for non-JVM targets)
// - miniduck (compilation errors in commonMain)
// - couch (depends on miniduck and tiny-btrfs)
// - uring (liburing FFI implementation issues)
// - polyglot (graalContext unresolved, syntax errors)
// - htx-client (ipfs/CAK/CAK manager unresolved references)
// - userspace-ebpf (unresolved references to send, program)
val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    libsDir.listFiles()!!
        .filter { it.isDirectory }
        .filter { it.name !in setOf("classfile", "miniduck-memory", "jvm-agent", "forge", "activejs", "tiny-btrfs", "miniduck", "couch", "user-signals", "ngsctp", "og1", "window-toolkit", "openapi", "ipfs", "miniduck", "couch", "uring", "polyglot", "htx-client", "userspace-ebpf") }
        .forEach { include(":libs:${it.name}") }
}

// Include lcnc
include(":libs:lcnc")