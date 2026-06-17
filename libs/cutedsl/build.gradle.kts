import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("maven-publish")
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                // TrikeShed cursor algebra for CursorTensor integration
                // TODO: Add when TrikeShed-jvm is published locally
                // compileOnly("borg.trikeshed:TrikeShed-jvm:1.0")
            }
        }

        getByName("jvmMain") {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                // For GPU interop path (CUDA)
                implementation("org.lwjgl:lwjgl:3.3.6")
                implementation("org.lwjgl:lwjgl-cuda:3.3.6")
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:6.0.3")
                implementation("org.junit.platform:junit-platform-engine:6.0.3")
                implementation("org.junit.platform:junit-platform-launcher:6.0.3")
            }
        }
    }
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failOnNoDiscoveredTests.set(false)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["kotlin"])
            groupId = "borg.trikeshed"
            artifactId = "cutedsl"
            version = "0.1.0-SNAPSHOT"
        }
    }
    repositories {
        mavenLocal()
    }
}