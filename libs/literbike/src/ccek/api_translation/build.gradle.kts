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
                // Rust: reqwest -> ktor-client
                api("io.ktor:ktor-client-core:3.1.2")
                api("io.ktor:ktor-client-content-negotiation:3.1.2")

                // Rust: serde + serde_json -> kotlinx-serialization-json
                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

                // Rust: anyhow -> Result<T> + sealed error classes (stdlib)
            }
        }
        val jvmMain by getting {
            dependencies {
                api("io.ktor:ktor-client-okhttp:3.1.2")
            }
        }
        val jsMain by getting {
            dependencies {
                api("io.ktor:ktor-client-js:3.1.2")
            }
        }
        val macosMain by getting {
            dependencies {
                api("io.ktor:ktor-client-darwin:3.1.2")
            }
        }
        val linuxMain by getting {
            dependencies {
                api("io.ktor:ktor-client-curl:3.1.2")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
