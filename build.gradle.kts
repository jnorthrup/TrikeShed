
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.4.0-Beta2"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
}

group = "org.bereft"
version = "1.0"
val enableNativeSharedLib = providers.gradleProperty("native.sharedLib").orNull == "true"

val focusedTransportSlice = providers.gradleProperty("focusedTransportSlice").orNull == "true"

// Centralized dependency versions available to all subprojects via project.extra
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
            // Kotlin 2.4 blocks user code in kotlin.* package — allow our non-JVM JvmInline stubs
            "-Xallow-kotlin-package",
        )
    }

    jvmToolchain(21)

    jvm {}

    js { nodejs() }

    @OptIn(ExperimentalWasmDsl::class) wasmJs {
        browser {
            testTask {
                val firefoxBin = project.file("/Applications/Firefox.app/Contents/MacOS/firefox")
                if (firefoxBin.exists()) {
                    useKarma {
                        useFirefox()
                    }
                } else {
                    useKarma {
                        useChromeHeadless()
                    }
                }
            }
        }
    }

    val hostOs: String  = System.getProperty("os.name")
    val hostArch: String = System.getProperty("os.arch")

    if (hostOs == "Mac OS X") {
        if (hostArch == "aarch64") {
            macosArm64("macos") {
                if (enableNativeSharedLib) {
                    binaries.sharedLib {
                        baseName = "trikeshed"
                    }
                }
                binaries.executable("autoresearchNative") {
                    entryPoint = "borg.trikeshed.autoresearch.autoresearchNativeMain"
                }
                // Local DuckDB cinterop removed from root build; if a local libs/duckdb is available,
                // include it via settings.gradle.kts as a composite build and add a proper cinterop there.
            }
        } else {
            // Intel mac
            macosX64("macos") {
                if (enableNativeSharedLib) {
                    binaries.sharedLib {
                        baseName = "trikeshed"
                    }
                }
                binaries.executable("autoresearchNative") {
                    entryPoint = "borg.trikeshed.autoresearch.autoresearchNativeMain"
                }
            }
        }
    }
    if (hostOs == "Linux") {
        linuxX64("linux") {
            compilations.getByName("main") {
                val zlinux_uring by cinterops.creating {
                    defFile(project.file("io_uring_interop/zlinux_uring.def"))
                }
            }
            if (enableNativeSharedLib) {
                binaries.sharedLib {
                    baseName = "trikeshed"
                }
            }
            binaries.executable("autoresearchNative") {
                entryPoint = "borg.trikeshed.autoresearch.autoresearchNativeMain"
            }
        }
        linuxArm64("linuxArm64") {
            if (enableNativeSharedLib) {
                binaries.sharedLib {
                    baseName = "trikeshed"
                }
            }
            binaries.executable("autoresearchNative") {
                entryPoint = "borg.trikeshed.autoresearch.autoresearchNativeMain"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0-rc02")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-rc02-0.6.x-compat")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
                implementation(project(":libs:miniduck"))
            }
        }
        val nativeMain by creating { dependsOn(commonMain) }
        val nativeTest by creating { dependsOn(commonTest) }
        val posixMain by creating {
            dependsOn(nativeMain)
        }
        val posixTest by creating {
            dependsOn(nativeTest)
        }
        if (hostOs == "Linux") {
            val linuxMain by getting { dependsOn(posixMain) }
            val linuxTest by getting { dependsOn(posixTest) }
        }

        val jvmMain by getting {
            resources.srcDir("src/jvmMain/resources")
            dependencies {
                // JMH dependencies for benchmarking
                implementation("org.openjdk.jmh:jmh-core:1.37")
                implementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")

                implementation("org.bouncycastle:bcprov-jdk15on:1.70")

                // Depend on libs/common JVM artifact for userspace/context implementations
            }

            // Include JMH benchmark sources in jvmMain for compilation
            kotlin.srcDir("src/jmhMain/kotlin")
            resources.srcDir("src/jmhMain/resources")

            // Include local DuckDB JVM sources when available so tests can compile
            kotlin.srcDir("libs/duckdb/src/jvmMain/kotlin")
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.junit.jupiter:junit-jupiter:6.1.0-RC1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
                implementation(project(":libs:server"))
                implementation(project(":libs:quic"))
                implementation(project(":libs:ngsctp"))
                implementation(project(":libs:htx-client"))
            }
        }

        val macosMain = sourceSets.findByName("macosMain"); macosMain?.dependsOn(sourceSets.getByName("posixMain"))
        val macosTest = sourceSets.findByName("macosTest"); macosTest?.run {
            dependsOn(posixTest)
        }

        val jsMain by getting {
            dependsOn(commonMain)
        }
        val jsTest by getting { dependsOn(commonTest) }
        val wasmJsMain by getting { dependsOn(commonMain) }
        val wasmJsTest by getting { dependsOn(commonTest) }
    }
}

subprojects {
    repositories {
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
        google()
        maven("https://www.jitpack.io")
    }
}

afterEvaluate {
    subprojects {
        repositories {
            maven("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenCentral()
            mavenLocal()
            gradlePluginPortal()
            google()
            maven("https://www.jitpack.io")
        }
    }

    if (System.getProperty("os.name") == "Mac OS X") {
        val macosTarget = kotlin.targets.getByName("macos") as KotlinNativeTarget
        macosTarget.binaries.all {
            linkerOpts.addAll(listOf("-L/opt/homebrew/lib"))
        }
    }

    tasks.register<Test>("focusedTransportTest") {
        description = "Runs the focused JVM transport/routing slice."
        group = "verification"
        val jvmTestComp = kotlin.targets.getByName("jvm").compilations.getByName("test")
        val jvmTestTask = tasks.named<Test>("jvmTest")
        testClassesDirs = jvmTestTask.get().testClassesDirs
        classpath =
            files(jvmTestComp.runtimeDependencyFiles, jvmTestComp.output.allOutputs, jvmTestTask.get().outputs.files)
        include("**/ChannelizationSelectionTest.class")
        include("**/ChannelizationProjectionTest.class")
        include("**/ProtocolRouterTest.class")
        include("**/SelectorTransportBackendTest.class")
        include("**/LinuxNativeTransportBackendTest.class")
        include("**/CcekTransportCapabilityTest.class")
        shouldRunAfter(jvmTestTask)
    }

    // JMH task configuration
    val jmhTask = tasks.register<JavaExec>("jmh") {
        description = "Runs JMH benchmarks"
        group = "benchmark"

        // Depend on compilation
        dependsOn("compileKotlinJvm")
        dependsOn("jvmJar")

        // Setup classpath with JMH dependencies and compiled classes
        val jvmComp = kotlin.targets.getByName("jvm").compilations.getByName("main")
        classpath = jvmComp.runtimeDependencyFiles ?: files()
        classpath += files(jvmComp.output.classesDirs)
        classpath += files(tasks.getByName("jvmJar").outputs.files)

        mainClass.set("org.openjdk.jmh.Main")
    }

    // Combined benchmark task
    tasks.register("benchmark") {
        description = "Runs all benchmarks (tests + JMH)"
        group = "verification"
        dependsOn("test")
        dependsOn(jmhTask)
    }

    // Set CHROME_BIN to wrapper that launches Chrome with required flags for headless runs
    val chromeWrapper = project.file("scripts/chrome-headless-wrapper.sh")
    val firefoxBin = project.file("/Applications/Firefox.app/Contents/MacOS/firefox")
    tasks.matching { it.name == "wasmJsBrowserTest" || it.name.endsWith("BrowserTest") }.configureEach {
        (this as? org.gradle.api.tasks.testing.Test)?.let {
            // Make CHROME_BIN available to the test process so Karma uses the wrapper
            it.environment("CHROME_BIN", chromeWrapper.absolutePath)
            // If a local Firefox binary exists, make FIREFOX_BIN available so Karma can use it
            if (firefoxBin.exists()) {
                it.environment("FIREFOX_BIN", firefoxBin.absolutePath)
            }
        }
    }
}

