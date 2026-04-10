import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
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
                // ccek-agent8888 has no external dependencies
                // Pure stdlib + CCEK core
                // Rust: tokio -> kotlinx-coroutines
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

                // Depends on ccek-core
                api(project(":src:ccek:core"))
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
