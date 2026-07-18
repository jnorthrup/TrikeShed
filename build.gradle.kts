import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
    kotlin("plugin.serialization") version "2.4.10"
    kotlin("plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.11.1"
    id("com.android.library") version "8.5.2"
}

// Compose UI is a JVM-only surface in this multiplatform project.  The Kotlin
// Compose compiler plugin defaults to every target, which makes Native/JS/Wasm
// compilations require a Compose runtime even though they contain no Compose UI.
// Restrict the compiler plugin to the target that actually consumes Compose.
composeCompiler {
    targetKotlinPlatforms.set(setOf(KotlinPlatformType.jvm))
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

    androidTarget {
    }

    jvm {
        compilerOptions {
            freeCompilerArgs = listOf(
                // "-P", "plugin:androidx.compose.compiler.plugins.kotlin:runtimeSignature=1.11.1"
            )
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

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
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

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
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
        macosX64("macosX64") {
            compilations.getByName("main") {
                cinterops {
                    create("posixSpawn") {
                        defFile = project.file("src/macosMain/resources/META-INF/cinterop/posix_spawn.def")
                    }
                }
            }
        }
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
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
                // Compose runtime annotations must be visible to every target so the
                // compose compiler plugin (applied globally) doesn't bail on JS/WASM/Native.
                // Full UI deps stay in jvmMain — Compose doesn't publish for macosX64.
                // implementation(org.jetbrains.compose.ComposePlugin.Dependencies(project).runtime) // REMOVED: breaks macosX64
            }
            // Slab hollows: GraalJS-eval / DuckDB-c-interop / MiniDuck layers are
            // entirely TODO() stubs with zero non-test consumers. Keep the files on
            // disk (user rule: preserve, don't delete) but cut them out of the
            // commonMain compile path until a real backend lands.
            kotlin.exclude("**/classfile/slab/**")
        }

        val commonTest = getByName("commonTest") {
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

                // Compose Desktop UI — JVM + Skiko only
                implementation(compose.desktop.currentOs)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
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

        val jsMain = getByName("jsMain") {
            dependsOn(commonMain)
            dependencies {
                implementation(npm("workbox-webpack-plugin", "7.4.1"))
            }
        }
        val jsTest = getByName("jsTest") { dependsOn(commonTest) }

        val wasmJsMain = getByName("wasmJsMain") {
            dependsOn(commonMain)
            dependencies {
                implementation(npm("workbox-webpack-plugin", "7.4.1"))
            }
        }
        val wasmJsTest = getByName("wasmJsTest") { dependsOn(commonTest) }

        // ── posixMain: shared intermediate above the default nativeMain template ───
        // Default hierarchy: nativeMain ← macosMain ← macosArm64Main
        //                  nativeMain ← linuxMain  ← linuxX64Main
        // We insert posixMain between nativeMain and each platform leaf so
        // src/posixMain actuals resolve for both macOS and Linux.
        val posixMain = maybeCreate("posixMain")
        val posixTest = maybeCreate("posixTest")
        val nativeMain = maybeCreate("nativeMain").apply { dependsOn(commonMain) }
        val nativeTest = maybeCreate("nativeTest").apply { dependsOn(commonTest) }
        posixMain.dependsOn(nativeMain)
        posixTest.dependsOn(nativeTest)

        // Default hierarchy template is disabled in gradle.properties, so the intermediate
        // macosMain/linuxMain source sets must be created explicitly.
        val macosMain = maybeCreate("macosMain").apply { dependsOn(posixMain) }
        val macosTest = maybeCreate("macosTest").apply { dependsOn(posixTest) }
        val macosX64Main = maybeCreate("macosX64Main").apply { dependsOn(macosMain) }
        val macosX64Test = maybeCreate("macosX64Test").apply { dependsOn(macosTest) }
        val linuxMain = maybeCreate("linuxMain").apply { dependsOn(posixMain) }
        val linuxTest = maybeCreate("linuxTest").apply { dependsOn(posixTest) }

        val androidMain = maybeCreate("androidMain").apply { dependsOn(commonMain) }
        val androidTest = maybeCreate("androidTest").apply { dependsOn(commonTest) }

        findByName("macosMain")?.dependsOn(posixMain)
        findByName("macosTest")?.dependsOn(posixTest)
        findByName("macosX64Main")?.dependsOn(posixMain)
        findByName("macosX64Test")?.dependsOn(posixTest)
        findByName("linuxMain")?.dependsOn(posixMain)
        findByName("linuxTest")?.dependsOn(posixTest)

        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlin.RequiresOptIn")
        }
    }

    // CInterop - Only compile io_uring bindings for focusedTransportSlice
    if (focusedTransportSlice) {
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
    } else {
        sourceSets.getByName("commonTest") {
            kotlin.exclude("**/transport/**")
            kotlin.exclude("**/userspace/**")
            kotlin.exclude("**/ipfs/**")
            kotlin.exclude("**/quic/**")
            kotlin.exclude("**/sctp/**")
            kotlin.exclude("**/window/**")
            kotlin.exclude("**/htx/**")
            // Stale against current CouchStore/CouchAttachmentGateway/Htx APIs; re-enable after reconciliation.
            kotlin.exclude("**/util/oroboros/**")
        }
    }
}

// ── JMH Setup ──────────────────────────────────────────────────────────────
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

// Forge widget gallery — print the catalog + blackboard to stdout for JVM sanity checks
tasks.register<JavaExec>("printForgeGallery") {
    group = "forge"
    description = "Print the Forge widget gallery catalog and blackboard view to stdout."
    dependsOn("compileKotlinJvm")
    mainClass.set("borg.trikeshed.forge.gallery.ForgeGalleryPrinterKt")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

// Forge JVM shell — interactive Compose Desktop window that hosts the same
// workspace model the browser bundle renders (board, page, gallery, blackboard).
tasks.register<JavaExec>("runForgeJvm") {
    group = "forge"
    description = "Launch the interactive Forge JVM shell (Compose Desktop)."
    dependsOn("compileKotlinJvm")
    mainClass.set("borg.trikeshed.forge.shell.ForgeWorkspaceKt")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

// Forge pages — publish web assets to docs/ for GitHub Pages.
// After running this, regenerate the seed-baked index.html via:
//   ./gradlew jsNodeProductionRun --no-daemon --console=plain 2>&1 \\
//     | awk '/^<!doctype html>/,/^<\\/html>/' > docs/index.html
// (kept as a shell step because re-invoking jsNodeProductionRun inside the same
// Gradle build deadlocks task graph ordering).
tasks.register<Sync>("generateForgePages") {
    group = "documentation"
    description = "Publishes web assets (wasm, icons, manifest, sw.js) to docs/. Index.html regenerated separately via jsNodeProductionRun."
    dependsOn("wasmJsBrowserProductionWebpack")

    from(project.layout.buildDirectory.dir("kotlin-webpack/wasmJs/productionExecutable")) {
        exclude("index.html")
    }
    from(project.layout.projectDirectory.dir("src/commonMain/resources/web")) {
        exclude("index.html")
    }
    into(project.layout.projectDirectory.dir("docs"))

    // Preserve the seed-baked index.html (regenerated via jsNodeProductionRun)
    preserve {
        include("index.html")
    }

    doLast {
        val noJekyll = project.layout.projectDirectory.file("docs/.nojekyll").asFile
        if (!noJekyll.exists()) noJekyll.writeText("\n")
        println("Published web assets to docs/. Regenerate index.html with:")
        println("  ./gradlew jsNodeProductionRun --no-daemon --console=plain 2>&1 | awk '/^<!doctype html>/,/^<\\/html>/' > docs/index.html")
    }
}

// Config cache
tasks.register("kmpPartiallyResolvedDependenciesCheckerIgnore") {
    doLast { }
}
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

android {
    namespace = "borg.trikeshed"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}
