import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
}

group = "org.bereft.ccek"
version = "0.1.0"

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
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

    jvm {}
    js(IR) { nodejs() }

    val hostOs = System.getProperty("os.name")
    if (hostOs == "Mac OS X") {
        macosArm64("macos") {}
    } else if (hostOs == "Linux") {
        linuxX64("linux") {}
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Depends on ccek-core
                api(project(":src:ccek:core"))

                // Rust: hkdf, sha2, x25519-dalek, subtle -> expect/actual crypto
                // These map to platform-specific crypto implementations:
                //   JVM: Java Security / BouncyCastle
                //   Native: CommonCrypto (macOS) / OpenSSL (Linux)
                //   JS: WebCrypto API
            }
        }
        val jvmMain by getting {
            dependencies {
                // JVM crypto via Java Security API
                // SHA-2, HKDF, X25519 available via javax.crypto and java.security
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                // Rust: rand -> kotlin.random.Random (stdlib)
            }
        }
    }
}
