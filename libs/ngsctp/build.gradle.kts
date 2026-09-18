plugins {
    kotlin("multiplatform")
}

group = "dev.jnorthrup"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvm {}

    linuxX64("native") {
        binaries {
            executable()
        }
    }

    js(IR) {
        browser()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Kotlin standard library
                api(kotlin("stdlib"))

                // Structured concurrency
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

                // Serialization
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
            }
        }

        val jvmMain by getting {
            dependencies {
                // Network sockets base (JVM only)
                api("io.ktor:ktor-network:3.0.0")
                // io_uring support via Ktor (JVM only)
                implementation("io.ktor:ktor-network-tls:3.0.0")
                // Netty for io_uring transport
                implementation("io.netty:netty-transport:4.1.117.Final")
                implementation("io.netty:netty-buffer:4.1.117.Final")
                implementation("io.netty:netty-common:4.1.117.Final")
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.1")
                // Logging
                implementation("org.slf4j:slf4j-api:2.0.16")
                implementation("ch.qos.logback:logback-classic:1.5.16")
                // kotlin-spirit-parser for zero-copy TLV parsing (JVM only) - local dependency
                // api("dev.jnorthrup:kotlin-spirit-parser:2.5.0")
            }
        }

        val nativeMain by getting {
            dependencies {
                // Native-specific networking
            }
        }

        val jsMain by getting {
            dependencies {
                // JavaScript-specific networking (WebSocket)
            }
        }
    }
}