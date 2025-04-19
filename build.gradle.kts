import org.jetbrains.kotlin.gradle.DeprecatedTargetPresetApi
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.1.20"   // Latest version of Kotlin Multiplatform as of now
    `maven-publish`
}

group = "com.vsiwest"
version = "1.0.0"

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    google()
    maven("https://www.jitpack.io")
}
publishing {
    publications {
        // Create a publication for each target
        kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().forEach { target ->
            create<MavenPublication>("${target.name}Publication") {
                from(components["kotlin"])
                groupId = project.group.toString()
                artifactId = "${project.name}-${target.name}"
                version = project.version.toString()
            }
        }
    }
    repositories {
        maven {
            url = uri("file://${System.getProperty("user.home")}/.m2/repository")
        }
    }
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class) compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn", // Add more opt-in annotations as needed
            "-Xsuppress-version-warnings", // Suppress version warnings
            "-Xexpect-actual-classes", // Enable expect/actual classes
        )
    }

    jvmToolchain(21)

    jvm {}

    val hostOs = System.getProperty("os.name")

    when {
        hostOs == "Mac OS X" -> if (System.getProperty("os.arch") == "aarch64") {
            macosArm64("posix")
        } else {
            macosX64("posix")
        }
        hostOs == "Linux" -> linuxX64("posix")
        hostOs == "Windows" -> mingwX64("posix")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val jvmMain by getting {
            dependencies {
                //datetime
                api("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.4.0")
                //coroutines
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.4")
                //serialization
             }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.6.4")

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
