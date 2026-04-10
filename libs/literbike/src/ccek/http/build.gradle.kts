import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
    kotlin("plugin.serialization") version "2.4.0-Beta1"
}

group = "org.bereft.ccek"
version = "0.1.0"

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
        )
    }

    jvmToolchain(21)

    jvm {}
    js(IR) { nodejs() }

    val hostOs = System.getProperty("os.name")
    if (hostOs == "Mac OS X") {
        macosArm64("macos") {}
    } else if (hostOs == "Linux") {
        linuxX64("linux") {}
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Rust: serde + serde_json -> kotlinx-serialization-json
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

                // Rust: bytes -> ByteArray / ByteReadPacket (stdlib)
                // For Ktor-based HTTP handling
                api("io.ktor:ktor-http:3.1.2")
                api("io.ktor:ktor-utils:3.1.2")

                // Rust: log -> kotlinx-coroutines (logging)
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

                // Rust: parking_lot -> @Synchronized / Mutex (stdlib)
                // Rust: socket2 -> expect/actual socket wrappers (stdlib)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
