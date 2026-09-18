plugins {
    kotlin("multiplatform") version "2.4.0"
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            }
            kotlin.srcDir("src/generated/kotlin")
            kotlin.srcDir("src/commonMain/kotlin")
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
            }
            kotlin.srcDir("src/commonTest/kotlin")
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
