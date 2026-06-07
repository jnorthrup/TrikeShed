pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

include(":libs:motion-estimation")
include(":libs:gguf")
include(":libs:mlx")
include(":libs:miniduck")
include(":libs:couchdb")
