import org.jetbrains.kotlin.gradle.DeprecatedTargetPresetApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.0.20"
    `maven-publish`
}

group = "org.bereft"
version = "1.0"

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
                binaries.sharedLib {
                    baseName = "trikeshed"
                }
                binaries.executable("brcCsvNative") {
                    entryPoint = "borg.trikeshed.brc.brcCsvNativeMain"
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
                binaries.sharedLib {
                    baseName = "trikeshed"
                }
                binaries.executable("brcCsvNative") {
                    entryPoint = "borg.trikeshed.brc.brcCsvNativeMain"
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
                            compilerOpts("-I/opt/homebrew/include")
                        }
                    }
                }
            }
        }
    } else if (hostOs == "Linux") {
        linuxX64("linux") {
            binaries.sharedLib {
                baseName = "trikeshed"
            }
            binaries.executable("brcCsvNative") {
                entryPoint = "borg.trikeshed.brc.brcCsvNativeMain"
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
        val posixTest by creating { dependsOn(nativeTest) }
        
        val jvmMain by getting {
            dependencies {
                implementation("org.duckdb:duckdb_jdbc:1.1.0")
                implementation("ai.hypergraph:kotlingrad:0.4.7")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val macosMain by creating { dependsOn(posixMain) }
        val macosTest by creating { dependsOn(posixTest) }
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
}
