plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {
        withJava()
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":src:commonMain"))
                // io_uring for high-performance async I/O
                implementation("io.netty:netty-transport:4.1.117.Final")
                implementation("io.netty:netty-buffer:4.1.117.Final")
                implementation("io.netty:netty-common:4.1.117.Final")
                // Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.1")
                // Logging
                implementation("org.slf4j:slf4j-api:2.0.16")
                implementation("ch.qos.logback:logback-classic:1.5.16")
            }
        }
    }
}
