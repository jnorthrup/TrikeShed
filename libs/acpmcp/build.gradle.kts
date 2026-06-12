import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.4.0"
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
        )
    }

    jvmToolchain(25)
    jvm()

    js(IR) {
        nodejs()
        generateTypeScriptDefinitions()
    }

    sourceSets {
        commonMain {
            dependencies {
                api("org.bereft:TrikeShed:1.0")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
            }
        }
        jvmMain {
            dependencies {
                implementation("borg.trikeshed:classfile:0.1.0-SNAPSHOT")
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnit()
}
