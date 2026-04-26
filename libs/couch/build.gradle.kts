import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
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

    jvmToolchain(21)
    jvm()

    js(IR) {
        nodejs()
        binaries.executable()
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
        val commonMain by getting {
            dependencies {
                api(project(":")) // root core published coordinates
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("reflect"))
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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }

        // POSIX source set shared by all native targets
        val posixMain by creating {
            dependsOn(commonMain)
        }

        // Each native target depends on posixMain + commonMain
        // Attach posixMain to any native main source sets present for the current host
        findByName("macosMain")?.let { it.dependsOn(posixMain) }
        findByName("linuxMain")?.let { it.dependsOn(posixMain) }
    }
}

// Quick validation runner used during local development. Not added to published plugin API.
// Usage: ./gradlew :libs:couch:quickValidate
tasks.register<org.gradle.api.tasks.JavaExec>("quickValidate") {
    group = "verification"
    description = "Run a quick jvmMain validation of MiniDuck encode/decode"
    dependsOn("compileKotlinJvm")
    // compiled classes for JVM target
    val classesDir = file("${'$'}{buildDir.path}/classes/kotlin/jvm/main")
    // try to find the runtime classpath for the jvm target
    val runtimeConfiguration = configurations.findByName("jvmRuntimeClasspath")
        ?: configurations.findByName("jvmMainRuntimeClasspath")
        ?: configurations.findByName("runtimeClasspath")
        ?: throw IllegalStateException("Could not locate jvm runtime classpath configuration")
    // For quick local validation, use compiled classes only (may still require kotlin stdlib at runtime).
    classpath = files(classesDir)
    mainClass.set("borg.trikeshed.couch.miniduck.MiniDuckQuickValidateKt")
    jvmArgs = listOf("-Xmx1g")
}
