
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("multiplatform") version "2.4.0-RC2"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
}

group = "org.bereft"
version = "1.0"
// Centralized dependency versions available to all subprojects via project.extra
extra["versions.kotlinx-coroutines-core"] = "1.11.0"
extra["versions.kotlinx-coroutines-test"] = "1.11.0"
extra["versions.kotlinx-datetime"] = "0.8.0-0.6.x-compat"

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    google()
    maven("https://www.jitpack.io")
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class) compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlin.ExperimentalUnsignedTypes",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
            // Kotlin 2.4 blocks user code in kotlin.* package — allow our non-JVM JvmInline stubs
            "-Xallow-kotlin-package",
        )
    }

    jvmToolchain(21)

    jvm {}

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
        val jvmMain by getting {
            resources.srcDir("src/jvmMain/resources")
            dependencies {
                // JMH dependencies for benchmarking
                implementation("org.openjdk.jmh:jmh-core:1.37")
                implementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")

                implementation("org.bouncycastle:bcprov-jdk15on:1.70")

                // Depend on userspace/context implementations via classpath (no libs/ subprojects)
            }

            // Include JMH benchmark sources in jvmMain for compilation
            kotlin.srcDir("src/jvmMain/kotlin")
            kotlin.srcDir("src/jmhMain/kotlin")
            resources.srcDir("src/jmhMain/resources")

            // Local DuckDB JVM sources unavailable (libs/ removed)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.compilerArgs.addAll(
                listOf(
                    "--add-exports=java.base/jdk.internal.classfile=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED"
                )
            )
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.junit.jupiter:junit-jupiter:6.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}

subprojects {
    repositories {
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
        google()
        maven("https://www.jitpack.io")
    }
}

afterEvaluate {
    subprojects {
        repositories {
            maven("https://oss.sonatype.org/content/repositories/snapshots/")
            mavenCentral()
            mavenLocal()
            gradlePluginPortal()
            google()
            maven("https://www.jitpack.io")
        }
    }

    tasks.register<Test>("focusedTransportTest") {
        description = "Runs the focused JVM transport/routing slice."
        group = "verification"
        val jvmTestComp = kotlin.targets.getByName("jvm").compilations.getByName("test")
        val jvmTestTask = tasks.named<Test>("jvmTest")
        testClassesDirs = jvmTestTask.get().testClassesDirs
        classpath =
            files(jvmTestComp.runtimeDependencyFiles, jvmTestComp.output.allOutputs, jvmTestTask.get().outputs.files)
        include("**/ChannelizationSelectionTest.class")
        include("**/ChannelizationProjectionTest.class")
        include("**/ProtocolRouterTest.class")
        include("**/SelectorTransportBackendTest.class")
        include("**/LinuxNativeTransportBackendTest.class")
        include("**/CcekTransportCapabilityTest.class")
        shouldRunAfter(jvmTestTask)
    }

    // JMH task configuration
    val jmhTask = tasks.register<JavaExec>("jmh") {
        description = "Runs JMH benchmarks"
        group = "benchmark"

        // Depend on compilation
        dependsOn("compileKotlinJvm")
        dependsOn("jvmJar")

        // Setup classpath with JMH dependencies and compiled classes
        val jvmComp = kotlin.targets.getByName("jvm").compilations.getByName("main")
        classpath = jvmComp.runtimeDependencyFiles ?: files()
        classpath += files(jvmComp.output.classesDirs)
        classpath += files(tasks.getByName("jvmJar").outputs.files)

        mainClass.set("org.openjdk.jmh.Main")
    }

    // Combined benchmark task
    tasks.register("benchmark") {
        description = "Runs all benchmarks (tests + JMH)"
        group = "verification"
        dependsOn("test")
        dependsOn(jmhTask)
    }
}

