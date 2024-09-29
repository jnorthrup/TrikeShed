<<<<<<< HEAD
plugins {
    kotlin("multiplatform") version "1.9.0"
}

kotlin {
    // JVM target
    jvm(  ) {
        withJava()
    }

    // JS target for WebAssembly/JavaScript
    js(IR) {
        browser()
        nodejs()
    }

    // Native POSIX targets
    macosX64("macos")
    linuxX64("linux")

    sourceSets {
        // Common code for all platforms (shared logic)
        val commonMain by getting
        val commonTest by getting

        // POSIX-compliant code (shared between macOS and Linux)
        val posixMain by creating {
            dependsOn(commonMain)
=======


import org.jetbrains.kotlin.gradle.DeprecatedTargetPresetApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

//which stanza do we add a linux64 cinteropdef for liburing below? (the linux64 stanza is the only one that has a cinterop block)

plugins {
    kotlin("multiplatform") version "2.0.20" // Latest version of Kotlin Multiplatform as of now
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
publishing {
    repositories {
        maven {
            url = uri("file://${System.getProperty("user.home")}/.m2/repository")
        }
    }
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class) compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn", // Add more opt-in annotations as needed
            "-Xsuppress-version-warnings", // Suppress version warnings
            "-Xexpect-actual-classes", // Enable expect/actual classes
        )
    }

    jvmToolchain(11)

    jvm {

        withJava()
    }


    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")

    when {
        hostOs == "Windows" -> mingwX64("windows")
        hostOs == "Mac OS X" -> if ( //aarch
            System.getProperty("os.arch") == "aarch64") {
            listOf(
                macosX64("macos"),

                ).first()
        } else {
            macosArm64("macos")
        }

        hostOs == "Linux" -> linuxX64("linux") // io_uring lives in linux sourceset only
        isMingwX64 -> mingwX64("posix")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")

    }

    sourceSets {

        val commonMain by getting {
            dependencies {

                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
                api("org.jetbrains.kotlin:kotlin-reflect:1.8.0")
            }
>>>>>>> posixish
        }

        // macOS-specific code
        val macosMain by getting {
            dependsOn(posixMain)
        }
<<<<<<< HEAD

        // Linux-specific code (liburing, etc.)
        val linuxMain by getting {
            dependsOn(posixMain)
        }

        // JVM-specific code
        val jvmMain by getting {
            dependsOn(commonMain)
=======
        val jvmMain by getting {
            dependencies {
                //datetime
                api("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.4.0")
                //coroutines
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
                //serialization
                api("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.3.0")
            }
>>>>>>> posixish
        }

        // JS-specific code
        val jsMain by getting {
            dependsOn(commonMain)
        }
<<<<<<< HEAD
    }
}
=======


        if (hostOs in listOf("Linux", "Mac OS X")) {

            //posix targets
            val nativeMain by creating {
                dependsOn(commonMain)
            }

            val nativeTest by creating {
                dependsOn(commonTest)
            }
            //posix targets
            val posixMain by creating {
                dependsOn(nativeMain)
            }

            val posixTest by creating {
                dependsOn(nativeTest)
            }

            when (hostOs) {
                "Linux" -> {
                    //io_uring
                    val linuxMain by getting {
                        dependsOn(posixMain)
                        dependencies {
                            implementation("org.bereft:io_uring:1.0")
                        }
                    }
                    val linuxTest by getting {
                        dependsOn(posixTest)
                    }
                }

                "Mac OS X" -> {
                    //libdispatch
                    val macosMain by getting {
                        dependsOn(posixMain)
                    }
                    val macosTest by getting {
                        dependsOn(posixTest)
                    }
                }

                else -> {
                    TODO("OS wtf!!")
                }
            }
        }
    }


}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinTest> {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

>>>>>>> posixish
