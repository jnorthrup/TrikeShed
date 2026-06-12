apply(from = "../../gradle/macros/trikeshed-lib.gradle")

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm {}
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(kotlinx.serialization("core"))
            }
        }
        val jvmMain by getting {}
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}
