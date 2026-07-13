plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {}
    linuxX64("native") {}

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(project(":"))
            }
        }
        val nativeMain by getting {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}
