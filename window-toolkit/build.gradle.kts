plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {}
    js { browser() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    linuxX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":"))
                api(project(":libs:miniduck"))
                api(project(":libs:user-signals"))

                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }

        val jvmMain by getting {
            dependencies {



            }
        }

        val linuxX64Main by getting {
            dependencies {

                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
    }
}
