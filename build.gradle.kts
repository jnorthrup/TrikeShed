
plugins {

    kotlin("multiplatform") version "1.7.20"
    id("org.jetbrains.intellij") version "1.9.+" apply false
    id("org.jetbrains.dokka") version "1.7.0"
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"

    //support kotlinx-datetime
    id("org.jetbrains.kotlin.plugin.serialization") version "1.7.20"

    //support for k2 compiler plugin
    id("org.jetbrains.kotlin.kapt") version "1.7.20"
    id("org.jetbrains.kotlin.plugin.allopen") version "1.7.20"
    id("org.jetbrains.kotlin.plugin.noarg") version "1.7.20"

}



group = "org.bereft"
version = "1.0"




repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    mavenCentral()
    mavenLocal()
       gradlePluginPortal()

}

kotlin {



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






        val nativeMain by getting
        val nativeTest by getting
        //add dep for  kotlinx.datetime.*
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")






            }
        }
    }
}

