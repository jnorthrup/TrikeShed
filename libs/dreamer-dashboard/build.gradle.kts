plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
}

group = "com.vsiwest"
version = "0.1.0-SNAPSHOT"

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    google()
    maven("https://www.jitpack.io")
}

kotlin {
    jvmToolchain(21)

    js(IR) {
        nodejs()
        binaries.executable()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.bereft:trikeshed")
                api("borg.trikeshed:common")
                api("borg.trikeshed:couch")
                api("borg.trikeshed:miniduck")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
    }
}
