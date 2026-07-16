import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "org.bereft"
version = "1.0"

// Locked versions — mirror the TrikeShed toolchain (see ../../build.gradle.kts)
val coroutinesVersion = "1.11.0"
val serializationVersion = "1.11.0"

repositories {
    google()
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://www.jitpack.io")
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.ExperimentalUnsignedTypes",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
            "-Xallow-kotlin-package",
        )
    }

    jvmToolchain(25)
    jvm()

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                // TrikeShed kernel algebra (Join, Series, α, ForgeDoc types) — resolved
                // via the composite includeBuild in settings.gradle.kts.
                implementation("org.bereft:TrikeShed:1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
            }
        }
        val jvmMain = getByName("jvmMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
            }
        }
        val jvmTest = getByName("jvmTest")
    }
}

// Catalog CLI — KMPP has no `run` task; wire a JavaExec so
// `./gradlew catalog --args='...'` works.
tasks.register<JavaExec>("catalog") {
    group = "application"
    description = "Run CatalogMain. Args: --args='<root-dir> [--out <file>] [--no-header]'"
    dependsOn("jvmJar")
    mainClass.set("org.bereft.ingest.jvm.CatalogMainKt")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
}

// Same compat guard TrikeShed uses — disable the KMPP plugin-config check task.
tasks.named("checkKotlinGradlePluginConfigurationErrors") {
    enabled = false
}
tasks.configureEach {
    if (name == "kmpPartiallyResolvedDependenciesChecker") {
        enabled = false
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
