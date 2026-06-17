plugins {
    kotlin("multiplatform") version "2.4.0"
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    jvm()
    js { nodejs() }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("org.graalvm.polyglot:polyglot:24.1.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0-rc02")
                implementation("org.bereft:TrikeShed-jvm:1.0")
            }
        }
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}