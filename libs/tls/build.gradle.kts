plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

kotlin {
    jvm {}
    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            }
        }
        val jvmMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}