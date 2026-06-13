pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

include(":libs:forge")
include(":libs:forge-ui")
include(":libs:lcnc")
include(":libs:polyglot")
include(":libs:motion-estimation")