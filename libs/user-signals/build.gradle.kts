plugins {
    kotlin("multiplatform") version "2.4.0"
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    google()
    maven("https://www.jitpack.io")
}

kotlin {
    jvmToolchain(25)

    jvm {}
    js {
        nodejs()
        binaries.executable()
    }
    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch")
    if (hostOs.contains("mac")) {
        if (hostArch == "aarch64") {
            macosArm64("macos") {
                binaries {
                    executable {
                        entryPoint = "borg.trikeshed.usersignals.gallery.main"
                    }
                }
            }
        }
    } else if (hostOs.contains("linux") && hostArch == "amd64") {
        linuxX64("linux") {
            binaries {
                executable {
                    entryPoint = "borg.trikeshed.usersignals.gallery.main"
                }
            }
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api(rootProject)  // root project has mutable package
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnit()
}

tasks.register<JavaExec>("runUserSignalsGalleryJvm") {
    group = "run"
    description = "Runs the user-signals gallery thin-slice demo on JVM."
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.usersignals.gallery.UserSignalsGalleryKt")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}
