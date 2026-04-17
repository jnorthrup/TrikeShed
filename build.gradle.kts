import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
    id("com.github.ben-manes.versions") version "0.53.0"
    `maven-publish`
}

group = "org.bereft"
version = "1.0"
val enableNativeSharedLib = providers.gradleProperty("native.sharedLib").orNull == "true"
val enableBrcTests = providers.gradleProperty("enableBrcTests").orNull == "true"
val focusedTransportSlice = providers.gradleProperty("focusedTransportSlice").orNull == "true"

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
        freeCompilerArgs =
            listOf(
                "-opt-in=kotlin.RequiresOptIn",
                "-Xsuppress-version-warnings",
                "-Xexpect-actual-classes",
            )
    }

    jvmToolchain(21)

    jvm {
    }

    js(IR) {
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    val hostOs = System.getProperty("os.name")

    if (hostOs == "Mac OS X") {
        if (System.getProperty("os.arch") == "aarch64") {
            macosArm64("macos") {
                if (enableNativeSharedLib) {
                    binaries.sharedLib {
                        baseName = "trikeshed"
                    }
                }
                binaries.executable("brcCsvNative") {
                    entryPoint = "borg.trikeshed.brc.brcCsvNativeMain"
                }
                binaries.executable("autoresearchNative") {
                    entryPoint = "borg.trikeshed.autoresearch.autoresearchNativeMain"
                }
                binaries.executable("brcCursorNative") {
                    entryPoint = "borg.trikeshed.brc.brcCursorNativeMain"
                }
                binaries.executable("brcDuckDbNative") {
                    entryPoint = "borg.trikeshed.brc.brcDuckDbNativeMain"
                }
                binaries.executable("brcIsamNative") {
                    entryPoint = "borg.trikeshed.brc.brcIsamNativeMain"
                }
                val duckdbHeader = file("/opt/homebrew/Cellar/duckdb/1.4.4/include/duckdb.h")
                if (duckdbHeader.exists()) {
                    compilations.getByName("main") {
                        cinterops {
                            create("duckdb") {
                                defFile(file("duckdb_interop/duckdb.def"))
                                compilerOpts("-I/opt/homebrew/include")
                            }
                        }
                    }
                    compilations.getByName("test") {
                        cinterops {
                            create("duckdb") {
                                defFile(file("duckdb_interop/duckdb.def"))
                                compilerOpts("-I/opt/homebrew/include")
                            }
                        }
                    }
                }
            }
        }
    } else if (hostOs == "Linux") {
        linuxX64("linux") {
            if (enableNativeSharedLib) {
                binaries.sharedLib {
                    baseName = "trikeshed"
                }
            }
            binaries.executable("brcCsvNative") {
                entryPoint = "borg.trikeshed.brc.brcCsvNativeMain"
            }
            binaries.executable("autoresearchNative") {
                entryPoint = "borg.trikeshed.autoresearch.autoresearchNativeMain"
            }
            binaries.executable("brcCursorNative") {
                entryPoint = "borg.trikeshed.brc.brcCursorNativeMain"
            }
            binaries.executable("brcDuckDbNative") {
                entryPoint = "borg.trikeshed.brc.brcDuckDbNativeMain"
            }
            binaries.executable("brcIsamNative") {
                entryPoint = "borg.trikeshed.brc.brcIsamNativeMain"
            }
            compilations.getByName("main") {
                cinterops {
                    create("duckdb") {
                        defFile(file("duckdb_interop/duckdb.def"))
                    }
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
            }
            // The old pseudo-common xio surface is retired in favor of JVM/NIO transport boundaries.
            kotlin.exclude("one/xio/NetworkChannel.kt")
            if (focusedTransportSlice) {
                kotlin.exclude("borg/trikeshed/grad/**")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
            if (focusedTransportSlice) {
                // Focused transport slice: exclude non-transport common tests
                kotlin.exclude("borg/trikeshed/parse/**")
            }
        }
        val nativeMain by creating { dependsOn(commonMain) }
        val nativeTest by creating { dependsOn(commonTest) }
        val posixMain by creating { dependsOn(nativeMain) }
        val posixTest by creating {
            dependsOn(nativeTest)
            // Current native duck test references unavailable symbols.
            kotlin.exclude("borg/trikeshed/duck/DuckFFITest.kt")
        }

        val jvmMain by getting {
            dependencies {
                // DuckDB JDBC - used for BRC benchmarks and DuckSeries tests
                // Arrangement note: DuckDB integration is intended for test/benchmark use only
                implementation("org.duckdb:duckdb_jdbc:1.5.1.0")

                // JMH dependencies for benchmarking
                implementation("org.openjdk.jmh:jmh-core:1.37")
                implementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")

                // Money/carlos JVM port from mp
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.0")
                implementation("com.squareup.okhttp3:okhttp:4.11.0")
                implementation("org.bouncycastle:bcprov-jdk15on:1.70")
            }

            // Include JMH benchmark sources in jvmMain for compilation
            kotlin.srcDir("src/jmhMain/kotlin")
            resources.srcDir("src/jmhMain/resources")

            // WIP experimental implementations; excluded from default build.
            kotlin.exclude("borg/trikeshed/brc/BrcDiscoveryOrder.kt")
            kotlin.exclude("borg/trikeshed/brc/BrcHashArray.kt")
            kotlin.exclude("borg/trikeshed/brc/BrcHeapBisect.kt")
            kotlin.exclude("borg/trikeshed/brc/BrcPure.kt")
            if (focusedTransportSlice) {
                kotlin.exclude("one/xio/AsioVisitor.kt")
                kotlin.exclude("borg/trikeshed/brc/BrcDuckDbJvm.kt")
                kotlin.exclude("borg/trikeshed/brc/BrcBenchmark.kt")
                kotlin.exclude("one/xio/HttpHeaders.kt")
                kotlin.exclude("one/xio/HttpMethod.kt")
                kotlin.exclude("rxf/server/CookieRfc6265Util.kt")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.junit.jupiter:junit-jupiter:5.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
            // WIP/experimental tests excluded from default build.
            kotlin.exclude("borg/trikeshed/signal/**")
            kotlin.exclude("borg/trikeshed/strategy/**")
            if (focusedTransportSlice) {
                // Focused transport slice: exclude all non-transport tests
                kotlin.exclude("gk/kademlia/**")
                kotlin.exclude("borg/trikeshed/parse/**")
                kotlin.exclude("borg/trikeshed/num/**")
                kotlin.exclude("borg/trikeshed/lib/**")
                kotlin.exclude("borg/trikeshed/common/**")
                kotlin.exclude("borg/trikeshed/duck/**")
                kotlin.exclude("borg/trikeshed/indicator/**")
                kotlin.exclude("borg/trikeshed/net/channelization/ChannelBlockExchangeTest.kt")
                kotlin.exclude("borg/trikeshed/net/channelization/ChannelSessionTest.kt")
            }
        }

        val macosMain by getting { dependsOn(posixMain) }
        val macosTest by getting {
            dependsOn(posixTest)
        }

        val jsMain by getting {
            dependsOn(commonMain)
            kotlin.exclude("borg/trikeshed/common/collections/**")
            kotlin.exclude("borg/trikeshed/hide/collections/**")
            kotlin.exclude("borg/trikeshed/http/SimpleHttpServer.kt")
            kotlin.exclude("borg/trikeshed/lib/descriptiveSetNotation.kt")
            kotlin.exclude("borg/trikeshed/lib/octals.kt")
        }
        val jsTest by getting { dependsOn(commonTest) }
        val wasmJsMain by getting { dependsOn(commonMain) }
        val wasmJsTest by getting { dependsOn(commonTest) }

        // Keep heavy 1BRC regression tests opt-in for local benchmarking.
        if (enableBrcTests) {
            jvmTest {
                kotlin.srcDir("src/brcTest/kotlin")
                resources.srcDir("src/brcTest/resources")
            }
        }
    }
}

afterEvaluate {
    if (System.getProperty("os.name") == "Mac OS X") {
        val macosTarget = kotlin.targets.getByName("macos") as KotlinNativeTarget
        macosTarget.binaries.all {
            linkerOpts.addAll(listOf("-L/opt/homebrew/lib", "-lduckdb"))
        }
    }

    // Task to print the JVM runtime classpath (used by brc harness scripts)
    tasks.register("printJvmClasspath") {
        dependsOn("jvmJar")
        doLast {
            val jvmMain =
                kotlin.targets
                    .getByName("jvm")
                    .compilations
                    .getByName("main")
            val cp = jvmMain.runtimeDependencyFiles!!.files.joinToString(":") { it.absolutePath }
            val jar =
                tasks
                    .getByName("jvmJar")
                    .outputs.files.singleFile.absolutePath
            println("$jar:$cp")
        }
    }

    if (enableBrcTests) {
        val jvmTestTask = tasks.named<Test>("jvmTest")
        jvmTestTask.configure {
            // Keep fast unit-test feedback in jvmTest; run BRC harness via brcTest.
            exclude("**/BrcHarnessTest.class")
        }

        // configure regression test task
        tasks.register<Test>("brcTest") {
            description = "Runs the BRC regression/acceptance tests"
            group = "verification"
            testClassesDirs = jvmTestTask.get().testClassesDirs
            classpath = jvmTestTask.get().classpath
            include("**/BrcHarnessTest.class")
            shouldRunAfter(jvmTestTask)
        }

        // ensure brcTest runs with "check"
        tasks.named("check") {
            dependsOn("brcTest")
        }
    }

    tasks.register<Test>("focusedTransportTest") {
        description = "Runs the focused JVM transport/routing slice."
        group = "verification"
        val jvmTestComp =
            kotlin.targets
                .getByName("jvm")
                .compilations
                .getByName("test")
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
    val jmhTask =
        tasks.register<JavaExec>("jmh") {
            description = "Runs JMH benchmarks"
            group = "benchmark"

            // Depend on compilation
            dependsOn("compileKotlinJvm")
            dependsOn("jvmJar")

            // Setup classpath with JMH dependencies and compiled classes
            val jvmComp =
                kotlin.targets
                    .getByName("jvm")
                    .compilations
                    .getByName("main")
            classpath = jvmComp.runtimeDependencyFiles ?: files()
            classpath += files(jvmComp.output.classesDirs)
            classpath += files(tasks.getByName("jvmJar").outputs.files)

            mainClass.set("borg.trikeshed.brc.BrcBenchmarkKt")
        }

    // Combined benchmark task
    tasks.register("benchmark") {
        description = "Runs all benchmarks (tests + JMH)"
        group = "verification"
        dependsOn("test")
        dependsOn(jmhTask)
    }
}
