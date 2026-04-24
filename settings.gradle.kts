pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    plugins {
        // Ensure included builds can resolve the Kotlin Multiplatform plugin
        id("org.jetbrains.kotlin.multiplatform") version "2.4.0-Beta1"
    }
}

rootProject.name = "TrikeShed"

// Explicitly include essential library subprojects to ensure the
// borg.trikeshed.* packages are available during multi-module compilation.
include(":libs:common")
project(":libs:common").projectDir = file("libs/common")

include(":libs:couch")
project(":libs:couch").projectDir = file("libs/couch")

include(":libs:dreamer-kmm")
project(":libs:dreamer-kmm").projectDir = file("libs/dreamer-kmm")

include(":libs:quic")
project(":libs:quic").projectDir = file("libs/quic")

include(":libs:ngsctp")
project(":libs:ngsctp").projectDir = file("libs/ngsctp")

include(":libs:openapi")
project(":libs:openapi").projectDir = file("libs/openapi")

include(":libs:htx-client")
project(":libs:htx-client").projectDir = file("libs/htx-client")

include(":libs:server")
project(":libs:server").projectDir = file("libs/server")

include(":libs:uring")
project(":libs:uring").projectDir = file("libs/uring")

include(":libs:kursive")
project(":libs:kursive").projectDir = file("libs/kursive")

// Integration harness project for end-to-end SQL→MiniDuck validation
include(":integration-scratch")
project(":integration-scratch").projectDir = file("integration-scratch")
