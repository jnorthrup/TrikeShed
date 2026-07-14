import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
    kotlin("plugin.serialization") version "2.4.0"
    kotlin("plugin.compose") version "2.4.0" apply false
}

group = "org.bereft"
version = "1.0"
val enableNativeSharedLib = providers.gradleProperty("native.sharedLib").orNull == "true"

val focusedTransportSlice = providers.gradleProperty("focusedTransportSlice").orNull == "true"

extra["versions.kotlinx-coroutines-core"] = "1.11.0"
extra["versions.kotlinx-coroutines-test"] = "1.11.0"
extra["versions.kotlinx-datetime"] = "0.8.0-0.6.x-compat"

val coroutinesVersion = extra["versions.kotlinx-coroutines-core"] as String
val coroutinesTestVersion = extra["versions.kotlinx-coroutines-test"] as String
val datetimeVersion = extra["versions.kotlinx-datetime"] as String

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
            "--add-exports=java.base/jdk.internal.classfile=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.attribute=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.models=ALL-UNNAMED",
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
                implementation("org.openjdk.jmh:jmh-core:1.37")
                implementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")
                implementation("org.bouncycastle:bcprov-jdk15on:1.70")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.graalvm.polyglot:polyglot:25.0.2")
                implementation("org.graalvm.polyglot:js-community:25.0.2")
                implementation("org.graalvm.polyglot:python-community:25.0.2")
                implementation("org.graalvm.truffle:truffle-api:25.0.2")
            }
            kotlin.srcDir("src/jmhMain/kotlin")
            resources.srcDir("src/jmhMain/resources")
        }
        val jvmTest by getting {
            kotlin.exclude("**/ConfixSerializationTest.kt")
            kotlin.exclude("**/ViewServerTest.kt")
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
                implementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
            }
        }
    }

    js {
        nodejs()
        browser {
            testTask {
                useKarma {
                    useConfigDirectory(project.layout.projectDirectory.dir("karma.config.d").asFile)
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        browser {
            testTask {
                useKarma {
                    useConfigDirectory(project.layout.projectDirectory.dir("karma.config.d").asFile)
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    linuxX64 {
        if (enableNativeSharedLib) {
            binaries.sharedLib {
                baseName = "trikeshed"
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.7.3")
            }
        }

        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesTestVersion")
            }
        }

        val jsMain = getByName("jsMain") { dependsOn(commonMain) }
        val jsTest = getByName("jsTest") { dependsOn(commonTest) }
        val wasmJsMain = getByName("wasmJsMain") { dependsOn(commonMain) }
        val wasmJsTest = getByName("wasmJsTest") { dependsOn(commonTest) }

        val linuxX64Main = getByName("linuxX64Main") { dependsOn(commonMain) }
        val linuxX64Test = getByName("linuxX64Test") { dependsOn(commonTest) }

        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlin.RequiresOptIn")
        }
    }
}

// CInterop - Only compile io_uring bindings for focusedTransportSlice
if (focusedTransportSlice) {
    kotlin {
        linuxX64 {
            compilations.getByName("main") {
                cinterops {
                    val liburing by creating {
                        defFile = project.file("io_uring_interop/liburing.def")
                        compilerOpts("-I${project.rootDir}/liburing/src/include", "-I${project.rootDir}/io_uring_interop")
                    }
                }
            }
        }
    }
} else {
    kotlin {
        sourceSets.getByName("commonTest") {
            kotlin.exclude("**/transport/**")
            kotlin.exclude("**/userspace/**")
            kotlin.exclude("**/ipfs/**")
            kotlin.exclude("**/quic/**")
            kotlin.exclude("**/sctp/**")
            kotlin.exclude("**/window/**")
            kotlin.exclude("**/htx/**")
        }
    }
}

// JVM args for internal APIs
val internalExports = listOf(
    "--add-exports", "java.base/jdk.internal.classfile=ALL-UNNAMED",
    "--add-exports", "java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
    "--add-exports", "java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
    "--add-exports", "java.base/jdk.internal.classfile.components=ALL-UNNAMED"
)

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(internalExports)
    options.compilerArgs.addAll(
        internalExports.map { "-J$it" }
    )
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    jvmArgs(*internalExports.toTypedArray())
}

// Karma config
tasks.named("jsNodeTest", org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest::class) {
    dependsOn("jsBrowserTest")
}
tasks.named("wasmJsNodeTest", org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest::class) {
    dependsOn("wasmJsBrowserTest")
}

// JMH Setup
tasks.register<JavaExec>("jmh") {
    dependsOn(":compileKotlinJvm")
    mainClass.set("org.openjdk.jmh.Main")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    args(".*", "-wi", "3", "-i", "5", "-f", "1")
}

tasks.register<JavaExec>("jmhJoin") {
    dependsOn(":compileKotlinJvm")
    mainClass.set("org.openjdk.jmh.Main")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    args("JoinBenchmark", "-wi", "5", "-i", "10", "-f", "1")
}

tasks.register<JavaExec>("jmhConfix") {
    dependsOn(":compileKotlinJvm")
    mainClass.set("org.openjdk.jmh.Main")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    args("ConfixDocCursorBenchmark", "-wi", "5", "-i", "10", "-f", "1")
}

tasks.register<JavaExec>("jmhWal") {
    dependsOn(":compileKotlinJvm")
    mainClass.set("org.openjdk.jmh.Main")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    args("ConfixWalAppendBenchmark", "-wi", "5", "-i", "10", "-f", "1")
}

tasks.register<JavaExec>("benchmarkJoin") {
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.lib.JoinBenchmarkRunner")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

tasks.register<JavaExec>("benchmarkSequence") {
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.lib.SequenceBenchmarkRunner")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

tasks.register<JavaExec>("benchmarkVector") {
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.lib.VectorBenchmarkRunner")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

tasks.register<JavaExec>("benchmarkMath") {
    dependsOn(":compileKotlinJvm")
    mainClass.set("org.openjdk.jmh.Main")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    args("MathJoinBenchmark", "-wi", "5", "-i", "10", "-f", "1")
}

tasks.register<JavaExec>("benchmarkConfix") {
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.parse.confix.ConfixBenchmarkRunner")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

// Config cache
tasks.register("kmpPartiallyResolvedDependenciesCheckerIgnore") {
    doLast { }
}
tasks.named("checkKotlinGradlePluginConfigurationErrors") {
    enabled = false
}
