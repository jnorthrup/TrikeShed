import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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

    jvmToolchain(21)
    jvm()

    js {
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
                api("org.bereft:TrikeShed:1.0")
                api(project(":libs:miniduck"))
                implementation(project(":libs:kursive"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-core").get()}")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:${rootProject.providers.gradleProperty("versions.kotlinx-datetime").get()}")
                api(project(":"))
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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-test").get()}")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(project(":libs:couch:viewserver"))
            }
        }
        // jsTest is independent of commonTest in KMP K2 (applyDefaultHierarchyTemplate=false)
        // — needs its own coroutines-test dependency for runTest, delay, launch, etc.
        val jsTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-core").get()}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-test").get()}")
            }
        }
        // wasmJsTest is also independent — same coroutines needs as jsTest
        val wasmJsTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-core").get()}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-test").get()}")
            }
        }

        val posixMain by creating {
            dependsOn(commonMain)
        }

        findByName("macosMain")?.let { it.dependsOn(posixMain) }
        findByName("linuxMain")?.let { it.dependsOn(posixMain) }
    }
}

tasks.register<JavaExec>("quickValidate") {
    group = "verification"
    description = "Run a quick jvmMain validation of MiniDuck encode/decode"
    dependsOn("compileKotlinJvm")
    val classesDir = file("${'$'}{buildDir.path}/classes/kotlin/jvm/main")
    val runtimeConfiguration = configurations.findByName("jvmRuntimeClasspath")
        ?: configurations.findByName("jvmMainRuntimeClasspath")
        ?: configurations.findByName("runtimeClasspath")
        ?: throw IllegalStateException("Could not locate jvm runtime classpath configuration")
    classpath = files(classesDir)
    mainClass.set("borg.trikeshed.couch.miniduck.MiniDuckQuickValidateKt")
    jvmArgs = listOf("-Xmx1g")
}
