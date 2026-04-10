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
                // Depends on ccek-core
                api(project(":src:ccek:core"))

                // Rust: tokio -> kotlinx-coroutines
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

                // Rust: serde + serde_json -> kotlinx-serialization-json
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

                // Rust: bincode -> kotlinx-serialization-cbor (binary serialization alternative)
                api("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.9.0")

                // Rust: bytes -> ByteArray / ByteReadPacket (stdlib)
                // Rust: base64 -> kotlinx-serialization or custom (stdlib base64)

                // Rust: parking_lot -> @Synchronized / Mutex (stdlib)
                // Rust: rand -> kotlin.random.Random (stdlib)
                // Rust: once_cell -> lazy / object (stdlib)
                // Rust: thiserror -> sealed exception classes (stdlib)
                // Rust: anyhow -> Result<T> + sealed error classes (stdlib)

                // Rust: crossbeam-channel -> kotlinx-coroutines channels
                // Rust: sha2 -> expect/actual crypto
                // Rust: blake3 -> expect/actual crypto
                // Rust: log -> kotlinx-coroutines (logging)
                // Rust: tracing -> custom logging wrappers
                // Rust: socket2 -> expect/actual socket wrappers
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
