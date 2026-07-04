import org.gradle.kotlin.dsl.`kotlin-dsl`
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "borg.trikeshed"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:6.1.0-RC1")
                implementation(kotlin("stdlib"))
            }
        }
    }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnitPlatform()
}