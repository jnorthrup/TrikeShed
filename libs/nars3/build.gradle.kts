plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    jvm()
    js { nodejs() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { nodejs() }
    val hostOs = System.getProperty("os.name")
    val hostArch = System.getProperty("os.arch")
    if (hostOs == "Mac OS X" && hostArch == "aarch64") macosArm64("macos")

    sourceSets {
        commonMain.dependencies {
            api(project(":libs:narsive"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("versions.kotlinx-coroutines-test")}")
        }
    }
}
