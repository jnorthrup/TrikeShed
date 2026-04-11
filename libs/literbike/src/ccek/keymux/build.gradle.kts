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

                // Rust: log -> kotlinx-coroutines (logging abstraction)
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

                // Rust: chrono + serde -> kotlinx-datetime + kotlinx-serialization
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")

                // Rust: parking_lot -> @Synchronized / Mutex (stdlib)
                // Rust: regex -> no direct equivalent; use stdlib regex (Kotlin 1.9+)
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
                // Rust: tempfile -> no direct equivalent; use stdlib temp dirs
            }
        }
    }
}
