plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
    mavenLocal()
    google()
    gradlePluginPortal()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://www.jitpack.io") }
}

kotlin {
    jvm {}

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":libs:lib"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}