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

                // Rust: serde + serde_json -> kotlinx-serialization-json
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

                // Rust: anyhow -> Result<T> + sealed error classes (stdlib)
                // Rust: thiserror -> sealed exception classes (stdlib)

                // Rust: async-trait -> Kotlin suspend functions (stdlib)
                // Rust: futures -> kotlinx-coroutines
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

                // Rust: parking_lot -> @Synchronized / Mutex (stdlib)
                // Rust: sha2 -> expect/actual crypto
                // Rust: hex -> custom hex utilities (stdlib)

                // Optional deps (feature-gated in Rust):
                //   sled -> embedded key-value (no direct Kotlin equivalent; use SQLDelight or custom)
                //   ipfs-api-backend-hyper -> ktor-client for IPFS
                //   reqwest -> ktor-client
                //   axum, tower, tower-http -> ktor-server
                //   chrono, uuid -> kotlinx-datetime
                //   notify -> custom file watchers
                //   git2 -> custom git wrappers
                //   ndarray, ndarray-linalg -> custom tensor math
                //   libpijul -> external FFI
                //   dashmap -> concurrent collections (stdlib)
                //   ring -> expect/actual crypto
            }
        }
        val jvmMain by getting {
            dependencies {
                // JVM-specific optional deps
                // Embedded DB: could use SQLDelight or SQLite
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
