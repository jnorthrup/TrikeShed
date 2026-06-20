import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
    kotlin("plugin.serialization") version "2.4.0" apply false
}

group = "org.bereft"
version = "1.0"
val enableNativeSharedLib = providers.gradleProperty("native.sharedLib").orNull == "true"

val focusedTransportSlice = providers.gradleProperty("focusedTransportSlice").orNull == "true"

extra["versions.kotlinx-coroutines-core"] = "1.10.1"
extra["versions.kotlinx-coroutines-test"] = "1.10.1"
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
            "-opt-in=kotlinx.cinterop.UnsafeNumber",
            "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
            "-Xallow-kotlin-package",
        )
    }

    jvmToolchain(25)

    jvm {}

    js {
        nodejs()
        browser {
            testTask {
                useKarma {
                    useConfigDirectory(project.layout.projectDirectory.dir("karma.config.d").asFile)
                    // The Kotlin/JS framework refuses to start the browser test task unless
                    // it has a browser in its internal list. We register ChromeHeadless so
                    // the "No browsers configured" check passes; the karma.config.d append
                    // then overrides the runtime browsers list to use karma-electron.
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    val hostOs = System.getProperty("os.name").lowercase()
    val isMac = hostOs.contains("mac")
    val isLinux = hostOs.contains("linux")

    if (isMac) {
        macosArm64("macos")
    }

    if (isLinux) {
        linuxX64("linux") {
            compilations.getByName("main") {
                val zlinux_uring by cinterops.creating {
                    defFile(project.file("io_uring_interop/zlinux_uring.def"))
                    compilerOpts("-I${project.rootDir}/liburing/src/include", "-I${project.rootDir}/io_uring_interop")
                }
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0-rc02")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-rc02-0.6.x-compat")
            }
        }
        val commonTest = getByName("commonTest") {
            kotlin.exclude(
                "**/demos/**",
                "**/strategy/**",
                "**/MutableSeriesStrategyTest.kt",
                "**/PointcutMutableSeriesTest.kt",
                "**/ReduxListBridgeTest.kt",
                "**/ReduxMutableSeriesTest.kt",
                "**/BtrfsCodecElementContractTest.kt"
            )
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
            }
        }
        val nativeMain = create("nativeMain") { dependsOn(commonMain) }
        val nativeTest = create("nativeTest") { dependsOn(commonTest) }
        val posixMain = create("posixMain") { dependsOn(nativeMain) }
        val posixTest = create("posixTest") { dependsOn(nativeTest) }

        val macosMain = sourceSets.findByName("macosMain"); macosMain?.dependsOn(posixMain)
        val macosTest = sourceSets.findByName("macosTest"); macosTest?.dependsOn(posixTest)
        val macosX64Main = sourceSets.findByName("macosX64Main"); macosX64Main?.dependsOn(posixMain); macosX64Main?.kotlin?.srcDir("src/macosMain/kotlin")
        val macosX64Test = sourceSets.findByName("macosX64Test"); macosX64Test?.dependsOn(posixTest); macosX64Test?.kotlin?.srcDir("src/macosTest/kotlin")

        val linuxMain = sourceSets.findByName("linuxMain"); linuxMain?.dependsOn(posixMain)
        val linuxTest = sourceSets.findByName("linuxTest"); linuxTest?.dependsOn(posixTest)
        val linuxArm64Main = sourceSets.findByName("linuxArm64Main"); linuxArm64Main?.dependsOn(posixMain); linuxArm64Main?.kotlin?.srcDir("src/linuxMain/kotlin")
        val linuxArm64Test = sourceSets.findByName("linuxArm64Test"); linuxArm64Test?.dependsOn(posixTest); linuxArm64Test?.kotlin?.srcDir("src/linuxTest/kotlin")

        val wasmJsMain = getByName("wasmJsMain") { dependsOn(commonMain) }
        val wasmJsTest = getByName("wasmJsTest") { dependsOn(commonTest) }

        val jvmMain = getByName("jvmMain") {
            resources.srcDir("src/jvmMain/resources")
            dependencies {
                implementation("org.openjdk.jmh:jmh-core:1.37")
                implementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")
                implementation("org.bouncycastle:bcprov-jdk15on:1.70")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.graalvm.polyglot:polyglot:25.0.2")
                implementation("org.graalvm.polyglot:js-community:25.0.2")
            }
            kotlin.srcDir("src/jmhMain/kotlin")
            resources.srcDir("src/jmhMain/resources")
        }
        val jvmTest = getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.junit.jupiter:junit-jupiter:6.1.0-RC1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
            }
        }
        val jsTest = getByName("jsTest") {
            dependencies {
                npm("karma-electron", "7.2.0")
                npm("electron", "31.7.7")
            }
        }
    }
}

apply(from = "publish_macro.gradle.kts")

tasks.named("publishMavenLocalMacro") {
    doFirst {
        println("=== Publishing TrikeShed to mavenLocal ===")
    }
}

tasks.named("cleanPublishMavenLocal") {
    doFirst {
        println("=== Clean build + publish to mavenLocal ===")
    }
}

// HTX Demo run task
tasks.register<JavaExec>("runHtxDemo") {
    group = "run"
    description = "Runs the HTX demo on JVM."
    dependsOn(":compileKotlinJvm")
    mainClass.set("borg.trikeshed.htx.demo.HtxDemoKt")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
}

// HTX Network Demo run task
tasks.register<JavaExec>("runHtxNetworkDemo") {
    group = "run"
    description = "Runs the HTX network demo on JVM."
    dependsOn(":compileKotlinJvm")
    mainClass.set("borg.trikeshed.htx.demo.HtxNetworkDemo")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
}


// Blackboard DAG Demo run task
tasks.register<JavaExec>("runBlackboardDagDemo") {
    group = "run"
    description = "Runs the Blackboard DAG Fabric demo on JVM."
    dependsOn(":compileKotlinJvm")
    mainClass.set("borg.trikeshed.dag.demo.BlackboardDagDemo")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
}

// ISAM Column Groupings Demo run task
tasks.register<JavaExec>("runIsamDemo") {
    group = "run"
    description = "Runs the ISAM Column Groupings demo on JVM."
    dependsOn(":compileKotlinJvm")
    mainClass.set("borg.trikeshed.isam.demo.IsamColumnGroupingsDemo")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
}

// CLI Demo run task
tasks.register<JavaExec>("runCliDemo") {
    group = "run"
    description = "Runs the CLI demo on JVM."
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.cli.demo.CliDemo")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

// OpenAPI Demo run task
tasks.register<JavaExec>("runOpenApiDemo") {
    group = "run"
    description = "Runs the OpenAPI demo on JVM."
    dependsOn(":libs:openapi:compileKotlinJvm")
    mainClass.set("borg.trikeshed.openapi.demo.OpenApiDemo")
    classpath(
        sourceSets.getByName("jvmMain").runtimeClasspath,
        project(":libs:openapi").sourceSets.getByName("jvmMain").output.classesDirs
    )
    workingDir = projectDir
}
