plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {}

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
    }
}
