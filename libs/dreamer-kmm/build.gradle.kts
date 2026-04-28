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
    js()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":"))
                api(project(":libs:miniduck"))
                api(project(":libs:couch"))
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
