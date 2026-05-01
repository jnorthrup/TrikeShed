pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

// Auto-include every libs/ subdirectory that has a build.gradle.kts.
file("libs").listFiles()?.filter { it.isDirectory }?.forEach { dir ->
    if (file("libs/${dir.name}/build.gradle.kts").exists()) {
        include(":libs:${dir.name}")
        project(":libs:${dir.name}").projectDir = dir
    }
}

// Nested modules
include(":libs:couch:viewserver")
project(":libs:couch:viewserver").projectDir = file("libs/couch/viewserver")
include(":libs:jules-client")
