import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }

    jvm()

    jvmToolchain(21)
    js {
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }

    val hostOs = System.getProperty("os.name")
    if (hostOs == "Mac OS X" && System.getProperty("os.arch") == "aarch64") {
        macosArm64("macos")
    } else if (hostOs == "Linux") {
        linuxX64("linux")
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":"))
                implementation(project(":libs:miniduck"))
                implementation(project(":libs:couch"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-test").get()}")
            }
        }
    }
}