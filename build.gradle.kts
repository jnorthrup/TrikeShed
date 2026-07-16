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
val enableBrowserTests = providers.gradleProperty("browserTests").orNull == "true"

val focusedTransportSlice = providers.gradleProperty("focusedTransportSlice").orNull == "true"
val viewServerNodeSlice = false

// ── Locked versions ───────────────────────────────────────────────────────
// GraalVM CE 25.0.2 is the locked runtime; JDK 25 toolchain.
val graalVersion = "25.0.2"

extra["versions.kotlinx-coroutines-core"] = "1.11.0"
extra["versions.kotlinx-coroutines-test"] = "1.11.0"
extra["versions.kotlinx-datetime"] = "0.8.0-0.6.x-compat"
extra["versions.kotlinx-serialization"] = "1.11.0"

val coroutinesVersion = extra["versions.kotlinx-coroutines-core"] as String
val coroutinesTestVersion = extra["versions.kotlinx-coroutines-test"] as String
val datetimeVersion = extra["versions.kotlinx-datetime"] as String
val serializationVersion = extra["versions.kotlinx-serialization"] as String

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

    jvm {
        compilerOptions {
            freeCompilerArgs = listOf(
            )
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            if (viewServerNodeSlice) {
                kotlin.setSrcDirs(listOf("src/viewServerCommonMain/kotlin"))
            }
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
            }
        }
        val commonTest = getByName("commonTest") {
            if (viewServerNodeSlice) {
                kotlin.setSrcDirs(listOf("src/viewServerCommonTest/kotlin"))
            }
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesTestVersion")
            }
        }
        val jvmMain = getByName("jvmMain") {
            resources.srcDir("src/jvmMain/resources")
            dependencies {
                implementation("org.openjdk.jmh:jmh-core:1.37")
                implementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")
                implementation("org.bouncycastle:bcprov-jdk15on:1.70")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

                // GraalVM Polyglot — locked to 25.0.2 (GraalVM CE)
                implementation("org.graalvm.polyglot:polyglot:$graalVersion")
                implementation("org.graalvm.polyglot:js-community:$graalVersion")
                implementation("org.graalvm.polyglot:python-community:$graalVersion")
                implementation("org.graalvm.truffle:truffle-api:$graalVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
            }
            kotlin.srcDir("src/jmhMain/kotlin")
            resources.srcDir("src/jmhMain/resources")
        }
        val jvmTest = getByName("jvmTest") {
            kotlin.exclude("**/ConfixSerializationTest.kt")
            kotlin.exclude("**/ViewServerTest.kt")
            kotlin.exclude("**/strategy/SignalValidationTest.kt")
            kotlin.exclude("**/demos/SignalBlackboardDemoTest.kt")
            kotlin.exclude("**/lib/ReduxListBridgeTest.kt")
            kotlin.exclude("**/lib/MutableSeriesStrategyTest.kt")
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
                implementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
                implementation("org.junit.vintage:junit-vintage-engine:5.10.2")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
            }
        }
    }

    js {
        nodejs()
        browser {
            testTask {
                enabled = enableBrowserTests
                useKarma {
                    useConfigDirectory(project.layout.projectDirectory.dir("karma.config.d").asFile)
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    wasmJs {
        nodejs()
        browser {
            testTask {
                enabled = enableBrowserTests
                useKarma {
                    useConfigDirectory(project.layout.projectDirectory.dir("karma.config.d").asFile)
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    // ── Host-detected native targets (restored from c0e3f0fc) ────────────────
    val hostOs = System.getProperty("os.name").lowercase()
    val isMac = hostOs.contains("mac")
    val isLinux = hostOs.contains("linux")

    if (isMac) {
        macosArm64("macos") {
            compilations.getByName("main") {
                cinterops {
                    val posixSpawn = create("posixSpawn") {
                        defFile = project.file("src/macosMain/resources/META-INF/cinterop/posix_spawn.def")
                    }
                }
            }
        }
        macosX64("macosX64")
    }

    if (isLinux || providers.gradleProperty("enableLinuxX64").orNull == "true") {
        linuxX64("linux") {
            if (enableNativeSharedLib) {
                binaries.sharedLib { baseName = "trikeshed" }
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
            }
        }

        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesTestVersion")
            }
        }

        val jsMain = getByName("jsMain") {
            dependsOn(commonMain)
            dependencies {
                implementation(npm("workbox-webpack-plugin", "7.4.1"))
            }
            if (viewServerNodeSlice) {
                kotlin.setSrcDirs(listOf("src/viewServerJsMain/kotlin"))
            }
        }
        val jsTest = getByName("jsTest") {
            dependsOn(commonTest)
            if (viewServerNodeSlice) {
                kotlin.setSrcDirs(emptyList<String>())
            }
        }
        val wasmJsMain = getByName("wasmJsMain") {
            dependsOn(commonMain)
            dependencies {
                implementation(npm("workbox-webpack-plugin", "7.4.1"))
            }
        }
        val wasmJsTest = getByName("wasmJsTest") { dependsOn(commonTest) }

        // ── posixMain: custom shared intermediate above the default template's ──
        // nativeMain.  The default hierarchy template creates:
        //   nativeMain ← macosMain ← macosArm64Main
        //   nativeMain ← linuxMain  ← linuxX64Main
        // We insert posixMain between nativeMain and each platform leaf so that
        // src/posixMain actuals resolve for both macos and linux.
        val posixMain = maybeCreate("posixMain")
        val posixTest = maybeCreate("posixTest")
        // Ensure nativeMain exists (template may create lazily), posixMain sits above it
        val nativeMain = maybeCreate("nativeMain").apply { dependsOn(getByName("commonMain")) }
        val nativeTest = maybeCreate("nativeTest").apply { dependsOn(getByName("commonTest")) }
        posixMain.dependsOn(nativeMain)
        posixTest.dependsOn(nativeTest)

        // Re-parent each platform intermediate onto posixMain (which itself
        // depends on nativeMain, so the chain posixMain→nativeMain→commonMain holds).
        // We add posixMain as a parent; the template's nativeMain parent is harmless
        // since posixMain sits above it in the same chain.
        findByName("macosMain")?.dependsOn(posixMain)
        findByName("macosTest")?.dependsOn(posixTest)
        findByName("linuxMain")?.dependsOn(posixMain)
        findByName("linuxTest")?.dependsOn(posixTest)

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
                    val liburing = create("liburing") {
                        defFile = project.file("src/linuxMain/resources/META-INF/cinterop/liburing.def")
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

// JVM args for internal APIs - not needed for JDK 25+ where ClassFile API is public
// val internalExports = listOf(
//     "--add-exports", "java.base/java.lang.classfile=ALL-UNNAMED",
//     "--add-exports", "java.base/java.lang.constant=ALL-UNNAMED"
// )

tasks.withType<JavaCompile>().configureEach {
    // No internal exports needed for JDK 25+ public ClassFile API
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    // No internal exports needed for JDK 25+
}

// Browser executables are published to GitHub Pages; browser tests are opt-in
// with -PbrowserTests=true. Node tests remain part of the default build.

// Ensure resources are copied before JVM compilation
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.contains("Jvm")) {
        dependsOn("jvmProcessResources")
    }
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
    args("borg.trikeshed.parse.confix.ConfixDocCursorBenchmark", "-wi", "5", "-i", "10", "-f", "1")
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
    mainClass.set("borg.trikiched.lib.SequenceBenchmarkRunner")
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

// Forge pages — publish the root KMPP JS browser target into docs/ for GitHub Pages
tasks.register<Sync>("generateForgePages") {
    group = "documentation"
    description = "Publishes the real root KMPP JS browser target into docs/ with .nojekyll."
    dependsOn("jsBrowserProductionWebpack")

    from(project.layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable"))
    from(project.layout.projectDirectory.dir("src/jsMain/resources"))
    into(project.layout.projectDirectory.dir("docs"))

    doLast {
        val noJekyll = project.layout.projectDirectory.file("docs/.nojekyll").asFile
        if (!noJekyll.exists()) {
            noJekyll.writeText("\n")
        }
        println("Published root KMPP JS browser target to ${project.layout.projectDirectory.dir("docs").asFile.absolutePath}")
    }
}

// Config cache
tasks.register("kmpPartiallyResolvedDependenciesCheckerIgnore") {
    doLast { }
}
tasks.named("checkKotlinGradlePluginConfigurationErrors") {
    enabled = false
}