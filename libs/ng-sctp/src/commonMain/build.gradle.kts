plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

kotlin {
    jvm {
        withJava()
    }
    
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
                
                // Network sockets base
                api("io.ktor:ktor-network:3.0.0")
                
                // kotlin-spirit-parser for zero-copy TLV parsing
                api("dev.jnorthrup:kotlin-spirit-parser:2.5.0")
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
                // io_uring support via Ktor
                implementation("io.ktor:ktor-network-tls:3.0.0")
                // Additional JVM-specific dependencies would go here:
                // - io_uring via custom JNI bindings
                // - eBPF via iovisor/bcc Java bindings
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
