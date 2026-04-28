plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    gradlePluginPortal()
    google()
    maven("https://www.jitpack.io")
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
        )
    }

    jvmToolchain(21)
    jvm()

    linuxX64()
    linuxArm64()
    val hostOs = System.getProperty("os.name")
    if (hostOs == "Mac OS X") {
        macosArm64()
        macosX64()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":"))
                implementation(kotlin("stdlib"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(project(":"))
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        // nativeMain intermediate source set
        val nativeMain by creating { dependsOn(commonMain) }
        val linuxX64Main by getting { dependsOn(nativeMain) }
        val linuxArm64Main by getting { dependsOn(nativeMain) }
        if (hostOs == "Mac OS X") {
            val macosArm64Main by getting { dependsOn(nativeMain) }
            val macosX64Main by getting { dependsOn(nativeMain) }
        }
    }
}
