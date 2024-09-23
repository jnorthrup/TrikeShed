plugins {
    kotlin("multiplatform") version "1.9.0"
}

kotlin {
    // JVM target
    jvm(  ) {
        withJava()
    }

    // JS target for WebAssembly/JavaScript
    js(IR) {
        browser()
        nodejs()
    }

    // Native POSIX targets
    macosX64("macos")
    linuxX64("linux")

    sourceSets {
        // Common code for all platforms (shared logic)
        val commonMain by getting
        val commonTest by getting

        // POSIX-compliant code (shared between macOS and Linux)
        val posixMain by creating {
            dependsOn(commonMain)
        }

        // macOS-specific code
        val macosMain by getting {
            dependsOn(posixMain)
        }

        // Linux-specific code (liburing, etc.)
        val linuxMain by getting {
            dependsOn(posixMain)
        }

        // JVM-specific code
        val jvmMain by getting {
            dependsOn(commonMain)
        }

        // JS-specific code
        val jsMain by getting {
            dependsOn(commonMain)
        }
    }
}