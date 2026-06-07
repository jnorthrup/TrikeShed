pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "TrikeShed"

include(":libs:motion-estimation")

// Support hybrid kotlin xvm build if ../xvm exists
if (java.io.File("../xvm").exists()) {
    includeBuild("../xvm")
}
