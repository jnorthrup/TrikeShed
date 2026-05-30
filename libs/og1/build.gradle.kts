plugins {
    kotlin("multiplatform")
    application
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

application {
    mainClass = "borg.trikeshed.og1.Og1Kt"
}

kotlin {
    jvmToolchain(21)
    jvm {
        jvmToolchain(21)
        withJava()
        testTask("jvmTest") {
            jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        }
    }
    js { nodejs() }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { nodejs() }
    val hostOs = System.getProperty("os.name")
    val hostArch = System.getProperty("os.arch")
    if (hostOs == "Mac OS X" && hostArch == "aarch64") macosArm64("macos")

    sourceSets {
        commonMain.dependencies {
            api(project(":"))
            api(project(":libs:cascade"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
