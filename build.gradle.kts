import org.jetbrains.kotlin.gradle.plugin.extraProperties
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform") version "1.8.0"
//    id("org.jetbrains.intellij") version "3.1" apply true

    id("org.jetbrains.dokka") version "1.7.0" apply false
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0" apply false

    // support kotlinx-datetime
    id("org.jetbrains.kotlin.plugin.serialization") version "1.8.0" apply false

    // support for k2 compiler plugin
    id("org.jetbrains.kotlin.kapt") version "1.8.0" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "1.8.0" apply false
    id("org.jetbrains.kotlin.plugin.noarg") version "1.8.0" apply false

    // gradle versions update plugin
    id("com.github.ben-manes.versions") version "0.42.0" apply false
//    id("atomicfu-gradle-plugin") version "0.18.5"
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
}


kotlin {
    jvmToolchain(18)

    jvm {

        withJava()
    }


//    val hostOs = System.getProperty("os.name")
//    val isMingwX64 = hostOs.startsWith("Windows")
//    val nativeTarget = when {
//        hostOs == "Mac OS X" -> macosX64("native")
//        hostOs == "Linux" -> linuxX64("native")
//        isMingwX64 -> mingwX64("native")
//        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
//    }
//we want to develop a linuxX64 target separately from generic native

    val nativeTarget = macosX64 ("native")
    val linuxX64Target = linuxX64("linuxX64")

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

        }

        val nativeTest by getting {

        }

        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }

        val linuxX64Test by getting {
            dependsOn(nativeTest)
        }


        val jvmMain by getting {
//            dependsOn(commonMain)
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
//    tasks.withType<KotlinCompile> {
//        kotlinOptions.freeCompilerArgs += "-opt-in"
//
//    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinTest> {
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
