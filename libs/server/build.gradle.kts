import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import java.io.File

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    google()
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
        )
    }

    jvmToolchain(21)
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api(project(":libs:common"))
                api(project(":libs:quic"))
                api(project(":libs:ngsctp"))
                api(project(":libs:htx-client"))
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

// Placeholder generator task for server-side artifacts (TDD)
val openApiGenerateHtxGeneralServer = tasks.register("openApiGenerateHtxGeneralServer") {
    group = "code generation"
    description = "Generates server placeholder files for TDD"

    doLast {
        val generatedRoot = layout.projectDirectory.dir("src/generated/kotlin").asFile
        val pkgDir = File(generatedRoot, "borg/trikeshed/server/generated")
        pkgDir.mkdirs()
        File(pkgDir, "Keys.kt").writeText("""
            package borg.trikeshed.server.generated

            object GeneratedKeys {
                const val SERVER_KEY = "htx-server"
            }
        """.trimIndent())
        File(pkgDir, "Elements.kt").writeText("""
            package borg.trikeshed.server.generated

            class GeneratedElements {
                fun placeholder() = "element"
            }
        """.trimIndent())
        File(pkgDir, "SupervisorJobs.kt").writeText("""
            package borg.trikeshed.server.generated

            object GeneratedSupervisorJobs {
                fun name() = "generated-supervisor"
            }
        """.trimIndent())
    }
}
