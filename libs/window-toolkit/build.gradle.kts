plugins {
    kotlin("multiplatform") version "2.4.0"
}

kotlin {
    jvm {}

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnit()
}