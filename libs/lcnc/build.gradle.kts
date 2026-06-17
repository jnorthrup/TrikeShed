import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    google()
    maven("https://www.jitpack.io")
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.ExperimentalUnsignedTypes",
            "-Xsuppress-version-warnings",
        )
    }

    sourceSets {
        val main by getting {
            dependencies {
                implementation("org.bereft:TrikeShed-jvm:1.0")
            }
        }
        val test by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
