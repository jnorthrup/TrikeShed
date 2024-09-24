import org.gradle.kotlin.dsl.support.kotlinCompilerOptions
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.ir.backend.js.compileIr

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
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
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
    val nativeTarget=
        when {
            hostOs == "Mac OS X" -> if ( //aarch
                System.getProperty("os.arch") == "aarch64"
            ) {
                macosX64("native")
            } else {
                macosArm64("native")
            }
            hostOs == "Linux" -> linuxX64("native") // io_uring lives in linux sourceset only
            isMingwX64 -> mingwX64("native")
            else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
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

        val nativeMain by getting {
            dependsOn(commonMain)
        }

        val nativeTest by getting {
            dependsOn(commonTest)
        }

        val jvmMain by getting {
            dependencies {
                //datetime
                api("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.4.0")
                //coroutines
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
                //serialization
                api("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.3.0")

            }
        }

        val jvmTest by getting {
            //bring in the dependencies from jvmMain
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinTest> {
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
