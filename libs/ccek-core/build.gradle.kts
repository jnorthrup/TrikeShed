plugins {
    id("org.jetbrains.kotlin.jvm")
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    google()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://www.jitpack.io") }
}

kotlin {
    jvmToolchain(25)

    sourceSets {
        val main by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val test by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>> {
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
        )
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    useJUnitPlatform()
}
