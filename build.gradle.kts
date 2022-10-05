plugins {
    kotlin("multiplatform") version "1.7.20"
    id("org.jetbrains.intellij") version "1.9.+" apply false
    id("org.jetbrains.dokka") version "1.7.0"
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"

    // support kotlinx-datetime
    id("org.jetbrains.kotlin.plugin.serialization") version "1.7.20"

    // support for k2 compiler plugin
    id("org.jetbrains.kotlin.kapt") version "1.7.20"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.7.20"
    id("org.jetbrains.kotlin.plugin.noarg") version "1.7.20"

    // gradle versions update plugin
    id("com.github.ben-manes.versions") version "0.42.0"
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
    jvm()
//    js      {
//        // To build distributions for and run tests on browser or Node.js use one or both of:
//        browser()
//        nodejs()
//    }
//    ios()

    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }

    sourceSets {

        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
            }
        }

        val commonTest by getting {
            //bring in the dependencies from commonMain
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val nativeMain by getting {
            //we like libcurl
            dependencies {
                //native coroutines
            }
        }

        val nativeTest by getting {
            //bring in the dependencies from nativeMain
            dependencies {
            }
        }

        val jvmMain by getting {
            dependencies {
                //datetime
                implementation("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.4.0")

                //coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.2")

                //serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.3.0")

            }
        }

        val jvmTest by getting

//        val jsMain by getting {
//            dependencies {  implementation(npm("@js-joda/timezone", "2.3.0"))
//            }
//        }
//
//        val jsTest by getting {
//            dependencies {
//                implementation(kotlin("test-js"))
//            }
//        }
//
//
//        val iosMain by getting
//        val iosTest by getting
    }
}
