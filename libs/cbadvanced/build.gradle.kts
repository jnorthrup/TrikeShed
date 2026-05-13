plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

val kotlinVersion = "2.4.0-Beta2"

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
        )
    }

    jvmToolchain(21)

    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun {
            mainClass.set(jvmMainClass)
        }
    }

    js {
        nodejs()
        binaries.executable()
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        binaries.executable()
    }

    val hostOs = System.getProperty("os.name")
    val hostArch = System.getProperty("os.arch")
    if (hostOs == "Mac OS X" && hostArch == "aarch64") {
        macosArm64("macos") {
            binaries.executable {
                entryPoint = "cbadvanced.main.main"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":"))
                implementation(project(":libs:dreamer-kmm"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("versions.kotlinx-coroutines-core")}")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("versions.kotlinx-coroutines-test")}")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(project(":"))
                implementation(project(":libs:dreamer-kmm"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
        val jsMain by getting
        val jsTest by getting { dependsOn(commonTest) }
        val wasmJsMain by getting
        val wasmJsTest by getting { dependsOn(commonTest) }
    }
}

val jvmMainClass = "cbadvanced.main.CbAdvancedMainKt"

// Auth proof task — run against the repo .env
tasks.register<JavaExec>("authProof") {
    group = "cbadvanced"
    description = "Run the Coinbase Advanced Trade auth sample against the repo .env"
    classpath = kotlin.targets["jvm"].compilations["main"].runtimeDependencyFiles!!
    mainClass.set(jvmMainClass)
    workingDir = project.projectDir
    jvmArgs("-ea")
}