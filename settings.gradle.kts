pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

// Only include the working modules
val workingModules = setOf(
    "forge", "forge-api", "forge-ui", "kanban", "keymux", "modelmux", "user-signals",
    "lcnc", "window-toolkit", "miniduck", "miniduck-memory", "common",
    "couch", "couch:viewserver", "htx-client", "tls", "ipfs", "quic", "ngsctp",
    "tiny-btrfs", "kursive", "patl", "concurrency", "dreamer-kmm", "dreamer-dashboard",
    "openapi", "server", "htx-client", "jules-client", "uring", "polyglot", "cmc",
    "cmc-generated", "krak", "krak-generated", "rhood-generated", "cpu-cache"
)

val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    libsDir.listFiles()!!
        .filter { it.isDirectory }
        .filter { it.name in workingModules }
        .forEach { include(":libs:${it.name}") }
}

// Include classfile subprojects if they have proper structure
if (file("libs/classfile/lib_cursor").exists()) {
    include(":libs:classfile:lib_cursor")
}

include(":libs:lcnc")
