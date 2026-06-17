import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform") version "2.1.0"
    id("java-library")
    id("maven-publish")
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvm {
        withJava()
        jvmToolchain(21)
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                // TrikeShed cursor algebra for CursorTensor integration
                implementation("borg.trikeshed:TrikeShed-jvm:1.0")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                // For GPU interop path (CUDA)
                implementation("org.lwjgl:lwjgl:3.3.6")
                implementation("org.lwjgl:lwjgl-cuda:3.3.6")
            }
        }

        val test by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:6.0.3")
                implementation("org.junit.platform:junit-platform-engine:6.0.3")
                implementation("org.junit.platform:junit-platform-launcher:6.0.3")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        ))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failOnNoDiscoveredTests.set(false)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "borg.trikeshed"
            artifactId = "cutedsl"
            version = "0.1.0-SNAPSHOT"
        }
    }
    repositories {
        mavenLocal()
    }
}