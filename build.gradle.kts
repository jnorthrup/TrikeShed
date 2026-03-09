import org.jetbrains.kotlin.gradle.DeprecatedTargetPresetApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.0.20"
    `maven-publish`
}

group = "org.bereft"
version = "1.0"
val enableNativeSharedLib = providers.gradleProperty("native.sharedLib").orNull == "true"
val enableBrcTests = providers.gradleProperty("enableBrcTests").orNull == "true"

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
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
        )
    }

    jvmToolchain(21)

    jvm {
        withJava()
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
        } else {
            macosX64("macos") {
                if (enableNativeSharedLib) {
                    binaries.sharedLib {
                        baseName = "trikeshed"
                    }
                }
                binaries.executable("brcCsvNative") {
                    entryPoint = "borg.trikeshed.brc.brcCsvNativeMain"
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
                            compilerOpts("-I/opt/homebrew/include")
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
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
                api("org.jetbrains.kotlin:kotlin-reflect:1.8.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
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
                implementation("org.duckdb:duckdb_jdbc:1.1.0")
                implementation("ai.hypergraph:kotlingrad:0.4.7")
            }
            // WIP experimental implementations; excluded from default build.
            kotlin.exclude("borg/trikeshed/brc/BrcDiscoveryOrder.kt")
            kotlin.exclude("borg/trikeshed/brc/BrcHashArray.kt")
            kotlin.exclude("borg/trikeshed/brc/BrcHeapBisect.kt")
            kotlin.exclude("borg/trikeshed/brc/BrcPure.kt")
            kotlin.exclude("borg/trikeshed/brc/fused/**")
            kotlin.exclude("borg/trikeshed/grad/**")
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
            // WIP/experimental tests excluded from default build.
            kotlin.exclude("borg/trikeshed/duck/KotlingradThinSliceTest.kt")
            kotlin.exclude("borg/trikeshed/grad/**")
            kotlin.exclude("borg/trikeshed/signal/**")
            kotlin.exclude("borg/trikeshed/strategy/**")
        }

        val macosMain by getting { dependsOn(posixMain) }
        val macosTest by getting {
            dependsOn(posixTest)
        }

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
            val jvmMain = kotlin.targets.getByName("jvm").compilations.getByName("main")
            val cp = jvmMain.runtimeDependencyFiles!!.files.joinToString(":") { it.absolutePath }
            val jar = tasks.getByName("jvmJar").outputs.files.singleFile.absolutePath
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
}
