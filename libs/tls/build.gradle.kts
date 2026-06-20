plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm {}
    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            }
        }
        val jvmMain = getByName("jvmMain") {
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