import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
}

group = "org.bereft"
version = "1.0"
val enableNativeSharedLib = providers.gradleProperty("native.sharedLib").orNull == "true"

val focusedTransportSlice = providers.gradleProperty("focusedTransportSlice").orNull == "true"

extra["versions.kotlinx-coroutines-core"] = "1.11.0-rc02"
extra["versions.kotlinx-coroutines-test"] = "1.11.0-rc02"
extra["versions.kotlinx-datetime"] = "0.8.0-rc02-0.6.x-compat"

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    google()
    maven("https://www.jitpack.io")
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class) compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.ExperimentalUnsignedTypes",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
            "-Xallow-kotlin-package",
            // JEP 484 ClassFile API (jdk.internal.classfile)
            "--add-exports=java.base/jdk.internal.classfile=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.attribute=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.models=ALL-UNNAMED",
        )
    }

    jvmToolchain(25)

    jvm {}

    js {
        nodejs()
        browser {
            testTask {
                useKarma {
                    useConfigDirectory(project.layout.projectDirectory.dir("karma.config.d").asFile)
                    // The Kotlin/JS framework refuses to start the browser test task unless
                    // it has a browser in its internal list. We register ChromeHeadless so
                    // the "No browsers configured" check passes; the karma.config.d append
                    // then overrides the runtime browsers list to use karma-electron.
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }
    // Electron host for jsBrowserTest (TDD: ElectronHostTest expects
    // process.versions.electron + Electron/<ver> in userAgent).
    // These devDeps live in the JS test source-set's npm scope so the
    // karma package.json installs them alongside the test runtime.
    sourceSets.named("jsTest").configure {
        dependencies {
            // devDependencies: karma-electron (launcher) + electron (browser binary)
            npm("karma-electron", "7.2.0")
            npm("electron", "31.7.7")
        }
    }

    sourceSets {
        named("commonMain").configure {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0-rc02")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-rc02-0.6.x-compat")
            }
        }
        named("commonTest").configure {
            kotlin.exclude(
                "**/demos/**",
                "**/strategy/**",
                "**/MutableSeriesStrategyTest.kt",
                "**/PointcutMutableSeriesTest.kt",
                "**/ReduxListBridgeTest.kt",
                "**/ReduxMutableSeriesTest.kt",
                "**/BtrfsCodecElementContractTest.kt"
            )
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
            }
        }
        named("jvmMain").configure {
            resources.srcDir("src/jvmMain/resources")
            dependencies {
                implementation("org.openjdk.jmh:jmh-core:1.37")
                implementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")
                implementation("org.bouncycastle:bcprov-jdk15on:1.70")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.graalvm.polyglot:polyglot:25.0.2+10.1")
            }
            kotlin.srcDir("src/jmhMain/kotlin")
            resources.srcDir("src/jmhMain/resources")
        }
        named("jvmTest").configure {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.junit.jupiter:junit-jupiter:6.1.0-RC1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
            }
        }
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}
