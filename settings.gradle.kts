pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
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

// Clean working modules - NO xvm, lib_cursor, polyglot, activejs, classfile
// We want: pointcutting algebra SEPARATED from xvm/lib_cursor
val workingModules = setOf(
    "forge", "forge-api", "forge-ui", "kanban", "keymux", "modelmux",
    "lcnc", "miniduck-memory", "common",
    "couch", "couch:viewserver", "htx-client", "tls", "ipfs", "quic",
    "tiny-btrfs", "kursive", "patl", "concurrency", "dreamer-kmm", "dreamer-dashboard",
    "openapi", "htx-client", "jules-client", "cmc",
    "cmc-generated", "krak", "krak-generated", "rhood-generated", "cpu-cache",
    "ccek-core", "lib", "classfile"
)

val brokenModules = setOf(
    "user-signals", "ngsctp", "miniduck", "uring", "ccek-dsl", "server", "window-toolkit", "lcnc"
)

val libsDir = rootDir.resolve("libs")
if (libsDir.exists() && libsDir.isDirectory) {
    libsDir.listFiles()!!
        .filter { it.isDirectory }
        .filter { it.name in workingModules }
        .filter { it.name !in brokenModules }
        .forEach { include(":libs:${it.name}") }
}
