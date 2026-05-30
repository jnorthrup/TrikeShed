pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

// Auto-include every libs/ subdirectory that has a build.gradle.kts,
// except broken ones that reference deleted confix types.
val excludedLibs = setOf(
    "miniduck", "openapi", "polyglot",
    "dreamer-kmm", "cbadvanced",
    "integration",  // macos klib missing — toolchain not configured
)

file("libs").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
    if (dir.name !in excludedLibs && file("libs/${dir.name}/build.gradle.kts").exists()) {
        include(":libs:${dir.name}")
        project(":libs:${dir.name}").projectDir = dir
    }
}