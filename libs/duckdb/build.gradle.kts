import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
}

group = "org.bereft"
version = "1.0"

repositories {
    mavenCentral()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    google()
    maven("https://www.jitpack.io")
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_3)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
        )
    }

    jvmToolchain(21)
    jvm {}
    js(IR) { nodejs() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    val hostOs = System.getProperty("os.name")
    if (hostOs == "Mac OS X" && System.getProperty("os.arch") == "aarch64") {
        macosArm64("macos") {}
    } else if (hostOs == "Linux") {
        linuxX64("linux") {}
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":"))  // depends on trikeshed core
            }
        }
        val commonTest by getting {
            dependencies { implementation(kotlin("test")) }
        }
        val nativeMain by creating { dependsOn(commonMain) }
        val nativeTest by creating { dependsOn(commonTest) }
        val posixMain by creating { dependsOn(nativeMain) }
        val posixTest by creating { dependsOn(nativeTest) }

        val jvmMain by getting {
            dependencies {
                implementation("org.duckdb:duckdb_jdbc:1.1.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.junit.jupiter:junit-jupiter:5.9.0")
            }
            // Add BRC harness sources
            kotlin.srcDir("src/brcTest/kotlin")
            resources.srcDir("src/brcTest/resources")
        }

        val macosMain by getting { dependsOn(posixMain) }
        val macosTest by getting { dependsOn(posixTest) }
        val jsMain by getting { dependsOn(commonMain) }
        val jsTest by getting { dependsOn(commonTest) }
        val wasmJsMain by getting { dependsOn(commonMain) }
        val wasmJsTest by getting { dependsOn(commonTest) }
    }
}

afterEvaluate {
    tasks.register<Test>("integrationTest") {
        description = "Runs BRC harness integration tests (DuckDB engine)"
        group = "verification"
        val jvmTestComp = kotlin.targets.getByName("jvm").compilations.getByName("test")
        val jvmTestTask = tasks.named<Test>("jvmTest")
        testClassesDirs = jvmTestTask.get().testClassesDirs
        classpath = files(jvmTestComp.runtimeDependencyFiles, jvmTestComp.output.allOutputs, jvmTestTask.get().outputs.files)
        include("**/BrcHarness*")
        shouldRunAfter(jvmTestTask)
    }

    tasks.named("check") {
        dependsOn("integrationTest")
    }
}
