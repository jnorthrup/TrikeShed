dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://www.jitpack.io")
    }
}

rootProject.name = "subvm"

// Deliberately NOT a composite build: unlike utils/ingest, this tree does not consume
// TrikeShed and TrikeShed does not consume it. Its only product is jars on disk under
// <module>/lib, which the Oroboros daemon mounts into a guest classloader at runtime.
// Wiring it with includeBuild("../..") would put these coordinates back on TrikeShed's
// classpath, which is the exact thing this directory exists to prevent.
