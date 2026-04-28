import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    google()
    maven("https://www.jitpack.io")
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

    js(IR) {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":"))
                implementation(project(":libs:couch"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val jsMain by getting {
            kotlin.srcDir("$buildDir/dukat")
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jsTest by getting
    }
}

// Gradle task to generate TypeScript declaration files from user JS
// Runs `npx tsc -p tsconfig.json` in the viewserver project directory.

tasks.register<org.gradle.api.tasks.Exec>("generateDts") {
    group = "build"
    description = "Run TypeScript compiler (npx tsc) to emit declaration files for user JS in libs/couch/viewserver/usercode"
    commandLine("npx", "tsc", "-p", "tsconfig.json")
    workingDir = project.projectDir
}

// Task to convert .d.ts declarations into Kotlin externs using dukat (node-based)
// Requires dukat to be available via npx (recommended) or installed globally.

tasks.register<org.gradle.api.tasks.Exec>("generateKotlinExterns") {
    group = "build"
    description = "Convert .d.ts outputs from tsc into Kotlin externs using dukat (npx dukat)."
    dependsOn("generateDts")
    // Input dir: build/dts (as configured in tsconfig.json)
    commandLine("npx", "dukat", "build/dts", "-o", "${buildDir}/dukat")
    workingDir = project.projectDir
}

// Generated Kotlin externs are added to the jsMain source set so Kotlin compilation can use them.
kotlin.sourceSets.getByName("jsMain").kotlin.srcDir("${buildDir}/dukat")

