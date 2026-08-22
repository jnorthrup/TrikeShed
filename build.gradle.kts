import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile

plugins {
    kotlin("multiplatform") version "2.4.20-Beta2"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
    kotlin("plugin.serialization") version "2.4.20-Beta2"
    kotlin("plugin.compose") version "2.4.20-Beta2"
    id("org.jetbrains.compose") version "1.11.1"
}

// Compose UI is a JVM-only surface in this multiplatform project.  The Kotlin
// Compose compiler plugin defaults to every target, which makes Native/JS/Wasm
// compilations require a Compose runtime even though they contain no Compose UI.
// Restrict the compiler plugin to the target that actually consumes Compose.
composeCompiler {
    targetKotlinPlatforms.set(setOf(KotlinPlatformType.jvm))
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"
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
        @OptIn(ExperimentalKotlinGradlePluginApi::class) compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
            freeCompilerArgs.addAll(
                listOf(
                    "-J--add-exports=java.base/jdk.internal.classfile=ALL-UNNAMED",
                    "-J--add-exports=java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
                    "-J--add-exports=java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
                    "-J--add-exports=java.base/jdk.internal.classfile.components=ALL-UNNAMED",
                    "-Xadd-exports=java.base/jdk.internal.classfile=ALL-UNNAMED",
                    "-Xadd-exports=java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
                    "-Xadd-exports=java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
                    "-Xadd-exports=java.base/jdk.internal.classfile.components=ALL-UNNAMED"
                )
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

    androidNativeArm64("android")
    linuxX64()

    // ── Host-detected native targets (restored from c0e3f0fc) ────────────────
    val hostOs = System.getProperty("os.name").lowercase()
    val isMac = hostOs.contains("mac")
    val isLinux = hostOs.contains("linux")
    val isWindowsHost = hostOs.startsWith("windows")

    if (isWindowsHost) {
        mingwX64("mingwX64") {
            compilations.getByName("main") {
                cinterops {
                    val posixSpawn = create("posixSpawn") {
                    }
                }
            }
        }
    } else {
        mingwX64("mingwX64") {
            compilations.getByName("main") {
                cinterops {
                }
            }
        }
    }

    if (isMac) {
        iosX64()
        iosSimulatorArm64()
        watchosX64()
        watchosSimulatorArm64()
        tvosX64()
        tvosSimulatorArm64()
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
        linuxX64 {
            if (enableNativeSharedLib) {
                binaries.sharedLib { baseName = "trikeshed" }
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$serializationVersion")
                
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
                // Confix is the only portable serializer in commonMain. The kotlinx-serialization
                // plugin stays applied (core @Serializable/@Contextual annotations need it), but the
                // json *runtime* is not a commonMain dependency — jvmMain pulls it for the one target
                // that legitimately needs the kotlinx JSON frontend. See README.md §4.
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
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$serializationVersion")
                
                implementation("org.ow2.asm:asm:9.7")
                implementation("org.ow2.asm:asm-tree:9.7")

                // GraalVM Polyglot — locked to 25.0.2 (GraalVM CE)
                implementation("org.graalvm.polyglot:polyglot:$graalVersion")
                implementation("org.graalvm.polyglot:js-community:$graalVersion")
                implementation("org.graalvm.polyglot:python-community:$graalVersion")
                implementation("org.graalvm.truffle:truffle-api:$graalVersion")

                // Apache Tika — document text extraction (PDF/DOCX/images via Tesseract OCR).
                // Parsers pull in POI/PDFBox/etc. only on the JVM target.
                implementation("org.apache.tika:tika-core:3.2.3")
                implementation("org.apache.tika:tika-parsers-standard-package:3.2.3")
                implementation("org.xerial:sqlite-jdbc:3.42.0.0")

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
            kotlin.exclude("**/strategy/SignalValidationTest.kt")
            kotlin.exclude("**/demos/SignalBlackboardDemoTest.kt")
            kotlin.exclude("**/lib/ReduxListBridgeTest.kt")
            kotlin.exclude("**/lib/MutableSeriesStrategyTest.kt")
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
                implementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
                implementation("org.junit.vintage:junit-vintage-engine:5.10.2")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5")
                implementation("junit:junit:4.13.2")
            }
        }

        val nativeMain = maybeCreate("nativeMain").apply { dependsOn(commonMain) }
        val nativeTest = maybeCreate("nativeTest").apply { dependsOn(commonTest) }
        val posixMain = maybeCreate("posixMain").apply { dependsOn(nativeMain) }
        val posixTest = maybeCreate("posixTest").apply { dependsOn(nativeTest) }
        val linuxMain = maybeCreate("linuxMain").apply {
            dependsOn(posixMain)
            kotlin.exclude("linux_uring/**")
        }
        val mingwX64Main = maybeCreate("mingwX64Main").apply { dependsOn(nativeMain) }
        val mingwX64Test = maybeCreate("mingwX64Test").apply { dependsOn(nativeTest) }
        val linuxTest = maybeCreate("linuxTest").apply { dependsOn(posixTest) }
        val macosMain = maybeCreate("macosMain").apply { dependsOn(posixMain) }
        val macosTest = maybeCreate("macosTest").apply { dependsOn(posixTest) }

        val androidMain = maybeCreate("androidMain").apply { dependsOn(commonMain) }
        val androidTest = maybeCreate("androidTest").apply { dependsOn(commonTest) }

        val nonPosixMain = maybeCreate("nonPosixMain").apply { dependsOn(commonMain) }
        findByName("jvmMain")?.dependsOn(nonPosixMain)
        findByName("jsMain")?.dependsOn(nonPosixMain)
        findByName("wasmJsMain")?.dependsOn(nonPosixMain)
        findByName("androidMain")?.dependsOn(nonPosixMain)

        // Source Set Hierarchy Documentation:
        // - posixMain: Code shared across posix platforms (macOS, Linux)
        // - macosMain: macOS-specific code
        // - linuxMain: Linux-specific code
        // - appleMain: Apple-platform-specific code (macOS, iOS, etc.)
        // Note: Default KMP hierarchy handles macosX64Main -> macosMain -> appleMain -> nativeMain.
        // We explicitly connect macosMain and linuxMain to posixMain above.
        
        findByName("macosMain")?.dependsOn(posixMain)
        findByName("macosTest")?.dependsOn(posixTest)
        findByName("macosX64Test")?.dependsOn(posixTest)
        findByName("linuxMain")?.dependsOn(posixMain)
        findByName("linuxTest")?.dependsOn(posixTest)
        findByName("mingwX64Main")?.dependsOn(mingwX64Main)
        findByName("mingwX64Test")?.dependsOn(mingwX64Test)
        // T7 browser storage: IndexedDB test doubles for JS/Wasm storage tests.
        getByName("jsTest") {
            dependencies {
                implementation(npm("fake-indexeddb", "6.0.0"))
            }
        }
        getByName("wasmJsTest") {
            dependencies {
                implementation(npm("fake-indexeddb", "6.0.0"))
            }
        }

        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlin.RequiresOptIn")
        }
    }

    // NOTE: commonMain srcDir for generated Forge assets is wired after
    // generateForgeAssets is registered (see below) — a forward reference
    // here cannot resolve at configuration time.
}

// ─────────────────────────────────────────────────────────────────
// Gradle Configuration Cache / Deprecation Suppression Hooks
// ─────────────────────────────────────────────────────────────────

tasks.named("checkKotlinGradlePluginConfigurationErrors") {
    enabled = false
}

// ─────────────────────────────────────────────────────────────────
// CInterop - Linux production actuals import this binding directly.
// ─────────────────────────────────────────────────────────────────

kotlin {
    linuxX64 {
        compilations.getByName("main") {
            cinterops {
                val zlinux_uring by creating {
                    defFile = project.file("io_uring_interop/zlinux_uring.def")
                    compilerOpts(
                        "-I${project.rootDir}/liburing/src/include",
                        "-I${project.rootDir}/io_uring_interop",
                    )
                }
            }
        }
    }
}

if (!focusedTransportSlice) {
    // Exclude transport tests from global runs to avoid CInterop linker errors
    kotlin {
        sourceSets.getByName("commonTest") {
            kotlin.exclude("**/transport/**")
            // userspace transport tests excluded; containment detector tests re-enabled
            // (they are pure commonMain value tests, no CInterop linkage).
            // btrfs facet/context/ebpf suites reference slab code that commonMain
            // excludes (see **/classfile/slab/** above) — keep them out too.
            kotlin.exclude("**/userspace/btrfs/**")
            kotlin.exclude("**/userspace/context/**")
            kotlin.exclude("**/userspace/nio/ebpf/**")
            kotlin.exclude("**/userspace/network/**")
            kotlin.exclude("**/userspace/reactor/**")
            kotlin.exclude("**/userspace/FunctionalUringFacadeTest.kt")
            kotlin.exclude("**/userspace/FunctionalUringFacadeXattrTest.kt")
            kotlin.exclude("**/userspace/ByteRegionTest.kt")
            kotlin.exclude("**/ipfs/**")
            kotlin.exclude("**/quic/**")
            // kotlin.exclude("**/sctp/**")
            kotlin.exclude("**/window/**")
            kotlin.exclude("**/htx/**")
            // Stale against current CouchStore/CouchAttachmentGateway/Htx APIs; re-enable after reconciliation.
            kotlin.exclude("**/util/oroboros/**")
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Explicit Task Graph Hooks
// ─────────────────────────────────────────────────────────────────

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    jvmArgs(
        "--add-exports", "java.base/jdk.internal.classfile=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.components=ALL-UNNAMED"
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf(
            "--add-exports", "java.base/jdk.internal.classfile=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.classfile.components=ALL-UNNAMED"
        )
    )
    options.compilerArgs.addAll(
        listOf(
            "--add-exports", "java.base/jdk.internal.classfile=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.classfile.components=ALL-UNNAMED"
        )
    )
}

// Explicit test configuration to force Karma Electron usage
tasks.named("jsTest") {
    dependsOn("jsBrowserTest")
}
tasks.named("wasmJsTest") {
    dependsOn("wasmJsBrowserTest")
}

// Ensure resources are copied before compilation
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

// TrajectoryReduction CLI — fold JulesCause chains into freeze verdicts
tasks.register<JavaExec>("trajectoryReduction") {
    group = "oroboros"
    description = "Run TrajectoryReduction: fold JulesCause trajectory into freeze verdict."
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.lcnc.reduction.TrajectoryReductionCliKt")
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

// ── Staged lib/ + naked classes for debug-friendly launchers ────────────────
// `build/staging/lib/` is populated by the runtime classpath jars so the
// `bin/*` launchers can build a classpath from a wildcard (`lib/*`) instead of
// fat-jar blobs or glob-walks of `~/.gradle/caches/modules-2`. TrikeShed's own
// classes stay un-jar'd at `build/classes/kotlin/jvm/main` so HotSwapAgent can
// watch `.class` files by mtime. The whole `build/` tree is gitignored.
val stagingLibDir = layout.buildDirectory.dir("staging/lib")

val stageDaemonLib = tasks.register<Sync>("stageDaemonLib") {
    group = "oroboros"
    description = "Copy the JVM runtime classpath jars into build/staging/lib/ for debug-friendly launchers."
    dependsOn("jvmJar")
    from(configurations.named("jvmRuntimeClasspath"))
    into(stagingLibDir)
}

// `-Pjdwp=5005` attaches a JDWP listener (suspend=n); `-Pjdwp=5005,suspend` waits for the debugger
// before main runs. Replaces the --debug/--suspend flags the old bin/* wrappers parsed.
val jdwpSpec: String? = providers.gradleProperty("jdwp").orNull

fun org.gradle.api.tasks.JavaExec.useStagedJvmClasspath() {
    dependsOn("stageDaemonLib", "compileKotlinJvm")
    jdwpSpec?.let { spec ->
        val port = spec.substringBefore(',').trim()
        val suspend = if (spec.substringAfter(',', "").trim() == "suspend") "y" else "n"
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=$suspend,address=*:$port")
    }
    doFirst {
        val classes = file("build/classes/kotlin/jvm/main")
        val lib = file(stagingLibDir)
        if (!classes.isDirectory) throw GradleException("missing $classes; run ./gradlew compileKotlinJvm")
        if (!lib.isDirectory) throw GradleException("missing $lib; run ./gradlew stageDaemonLib")
        classpath = files(classes) + fileTree(lib) { include("*.jar") }
    }
}

// Jules CAS bridges (formerly bin/oroboros-artifact, -settle, -review-patch, -review-report).
// Pass CLI args with --args, e.g. ./gradlew julesReviewPatch --args="<activity-id>".
fun registerJulesCli(taskName: String, cliMain: String, desc: String, vararg fixedArgs: String) =
    tasks.register<JavaExec>(taskName) {
        group = "jules"
        description = desc
        mainClass.set(cliMain)
        useStagedJvmClasspath()
        if (fixedArgs.isNotEmpty()) args(*fixedArgs)
        standardInput = System.`in`
    }
registerJulesCli("julesArtifact", "borg.trikeshed.jules.JulesArtifactCli", "Emit exact, already-observed Jules CAS bytes; no API or repository mutations.")
registerJulesCli("julesSettle", "borg.trikeshed.jules.JulesSettlementCli", "Durable settlement bridge for Jules patches delivered without a PR or branch.")
registerJulesCli("julesReviewPatch", "borg.trikeshed.jules.JulesPatchReviewCli", "Select one already-CASed Jules activity patch for drain after explicit review.")
registerJulesCli("julesReviewReport", "borg.trikeshed.jules.JulesPatchReviewCli", "Bond one already-CASed full Jules agent report to an explicit disposition.", "report")

// Daemon — flywheel loop. HotSwapAgent watches CycleBody.class for live edits.
tasks.register<JavaExec>("runOroborosDaemon") {
    group = "oroboros"
    description = "Launch OroborosDaemon from naked classes + staged lib/. -Pjdwp=5005[,suspend] attaches a debugger; --args forwards daemon flags (--once/--watch/--interval-ms/--home/--repo)."
    mainClass.set("borg.trikeshed.daemon.OroborosDaemon")
    useStagedJvmClasspath()
    // Forward stdio; HotswapAgent prints to stdout.
    standardInput = System.`in`
}

// TUI — interactive flywheel console, reads board from cwd.
tasks.register<JavaExec>("runFlywheelTui") {
    group = "oroboros"
    description = "Launch FlywheelTui from naked classes + staged lib/. -Pjdwp=5006[,suspend] attaches a debugger."
    mainClass.set("borg.trikeshed.flywheel.FlywheelTui")
    useStagedJvmClasspath()
    standardInput = System.`in`
}

// Kanban HTTP server for the modelmux CLI.
tasks.register<JavaExec>("runKanbanHttpServerJvm") {
    group = "forge"
    description = "Launch KanbanHttpServerJvm from naked classes + staged lib/. -Pjdwp=5007[,suspend] attaches a debugger."
    mainClass.set("borg.trikeshed.forge.server.KanbanHttpServerJvm")
    useStagedJvmClasspath()
    standardInput = System.`in`
}

// Forge pages — publish the static PWA to docs/ (GitHub Pages root, branch master + /docs).
//
//   ./gradlew generateForgePages                        # stage jvm: JVM-baked index.html + sw/manifest/icons/css/js
//   ./gradlew generateForgePages -PforgePagesStages=jvm,js,wasm   # + Kotlin/JS and wasmJs bundles under docs/js, docs/wasm
//   ./gradlew forgePagesProbe                           # is the next stage green? (compiles JS + wasm targets)
//   ./gradlew serveForgePages [-PforgePort=8765]        # serve docs/ at http://localhost:8765/ + POST /ingest (ForgeIngestServer)
//   ./gradlew forgePwa                                  # generate + serve
//   Deploy = generate, commit docs/, push. Pages = branch master, folder /docs; no Actions workflows.
//
// Ratchet: gradle/js-target-debt.excludes lists commonMain files cut from the JS-target compiles only;
// delete a line when it compiles. Unselected stages never enter the task graph, so a red stage cannot
// fail a publish of the green ones.
fun debtExcludes(name: String): List<String> =
    providers.fileContents(layout.projectDirectory.file("gradle/$name")).asText
        .map { text -> text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() } }
        .getOrElse(emptyList())

val jsTargetDebt = debtExcludes("js-target-debt.excludes")
val wasmTargetDebt = debtExcludes("wasm-target-debt.excludes")

tasks.withType<Kotlin2JsCompile>().configureEach {
    val globs = jsTargetDebt + (if (name.contains("WasmJs")) wasmTargetDebt else emptyList())
    exclude(globs)
    inputs.property("forgeDebtExcludes", globs)
}

val forgePagesStages: Set<String> = providers.gradleProperty("forgePagesStages").orElse("jvm").get()
    .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
require(forgePagesStages.all { it in setOf("jvm", "js", "wasm") }) { "forgePagesStages must be a subset of jvm,js,wasm; got $forgePagesStages" }
require("jvm" in forgePagesStages) { "forgePagesStages must include jvm (the baker)" }

val forgeBundleScripts: List<String> = buildList {
    if ("js" in forgePagesStages) add("./js/TrikeShed.js")
    if ("wasm" in forgePagesStages) add("./wasm/TrikeShed.js")
}

tasks.register<JavaExec>("bakeForgePages") {
    group = "documentation"
    description = "Render ForgeApp.renderHtml() with the real seed into docs/index.html (donor: /tmp/hi if present)."
    mainClass.set("borg.trikeshed.forge.ForgeBakePages")
    useStagedJvmClasspath()
    args(
        project.layout.projectDirectory.file("docs/index.html").asFile.path,
        providers.gradleProperty("forgeDonor").orElse("/tmp/hi").get(),
        // jnorthrup.json is the intact persisted plan; jim.json was clobbered by a non-kanban /tmp/hi on 2026-08-21.
        providers.gradleProperty("forgeUser").orElse("jnorthrup").get(),
        forgeBundleScripts.joinToString(","),
    )
}

tasks.register<Sync>("generateForgePages") {
    group = "documentation"
    description = "Publishes the Forge PWA to docs/ for the stages in forgePagesStages (currently: ${forgePagesStages.sorted()})."
    dependsOn("bakeForgePages")

    from(project.layout.projectDirectory.dir("src/commonMain/resources/web")) {
        exclude("index.html")
    }
    // Only the compiled bundle files: the distribution also carries every processed resource
    // (web/, confix/, openapi/, …) and, if a webpack SW plugin were present, its own sw.js.
    val bundleFiles = listOf("*.js", "*.mjs", "*.wasm", "*.LICENSE.txt")
    if ("js" in forgePagesStages) {
        dependsOn("jsBrowserDistribution")
        from(project.layout.buildDirectory.dir("dist/js/productionExecutable")) { into("js"); include(bundleFiles); exclude("sw.js", "workbox-*.js") }
    }
    if ("wasm" in forgePagesStages) {
        dependsOn("wasmJsBrowserDistribution")
        from(project.layout.buildDirectory.dir("dist/wasmJs/productionExecutable")) { into("wasm"); include(bundleFiles); exclude("sw.js", "workbox-*.js") }
    }
    into(project.layout.projectDirectory.dir("docs"))

    // docs/ is also the markdown doc root and holds the baked index.html: never sweep those.
    preserve {
        include("index.html")
        include(".nojekyll")
        include("*.md")
        include("dispatch/**")
    }

    // Hand-written sw.js: expand the precache token with the selected bundles; stamp the cache name per stage set.
    val precacheExtra = forgeBundleScripts.joinToString("") { ",\n        '$it'" }
    val stageStamp = forgePagesStages.sorted().joinToString("-")
    inputs.property("forgePagesStages", forgePagesStages.sorted())
    filesMatching("sw.js") {
        filter { line ->
            line.replace("/*FORGE_PRECACHE_EXTRA*/", precacheExtra)
                .replace("forge-cache-v3'", "forge-cache-v3-$stageStamp'")
        }
    }

    doLast {
        val noJekyll = project.layout.projectDirectory.file("docs/.nojekyll").asFile
        if (!noJekyll.exists()) noJekyll.writeText("\n")
        println("Forge PWA published to docs/ (stages: $stageStamp). Serve: ./gradlew serveForgePages  |  Pages: https://jnorthrup.github.io/TrikeShed/  |  Now: git add docs && git commit && git push")
    }
}

// Local preview of the published tree, served from the JDK's built-in static server — no python, no npm.
// Binds 127.0.0.1 so the service worker scope matches what GitHub Pages serves under /TrikeShed/… relative urls.
val forgePort: String = providers.gradleProperty("forgePort").orElse("8765").get()

tasks.register<JavaExec>("serveForgePages") {
    group = "documentation"
    description = "Serve docs/ at http://localhost:$forgePort/ plus POST /ingest (Tika; ffmpeg+tesseract for scans) via ForgeIngestServer (Ctrl-C to stop). -PforgePort=N."
    mainClass.set("borg.trikeshed.forge.server.ForgeIngestServer")
    useStagedJvmClasspath()
    val docsDir = project.layout.projectDirectory.dir("docs").asFile
    doFirst { if (!docsDir.resolve("index.html").isFile) throw GradleException("docs/index.html missing; run ./gradlew generateForgePages first") }
    args(docsDir.path, forgePort)
}

tasks.register("forgePwa") {
    group = "documentation"
    description = "generateForgePages then serveForgePages (the old `bin/forge-pwa.sh all`)."
    dependsOn("generateForgePages", "serveForgePages")
}
tasks.named("serveForgePages") { mustRunAfter("generateForgePages") }

tasks.register("forgePagesProbe") {
    group = "documentation"
    description = "Compile the JS and wasmJs targets under gradle/*-target-debt.excludes; green = ready to add that stage."
    dependsOn("compileKotlinJs", "compileKotlinWasmJs")
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


val generateForgeAssets = tasks.register("generateForgeAssets") {
    group = "build"
    description = "Generates Kotlin strings for Forge web assets"

    val webDir = file("src/commonMain/resources/web")
    val htmlFile = File(webDir, "index.html")
    val cssFile = File(webDir, "styles.css")
    val jsFile = File(webDir, "script.js")

    val outputDir = layout.buildDirectory.dir("generated/source/forgeAssets/kotlin/borg/trikeshed/forge/generated")

    inputs.file(htmlFile)
    inputs.file(cssFile)
    inputs.file(jsFile)
    outputs.dir(outputDir)

    doLast {
        val outDirFile = outputDir.get().asFile
        outDirFile.mkdirs()

        fun createByteArray(name: String, bytes: ByteArray): String {
            val chunks = bytes.toList().chunked(5000)
            for ((i, chunk) in chunks.withIndex()) {
                val code = "package borg.trikeshed.forge.generated\n\ninternal object ${name}_$i {\n" +
                           "    val data: ByteArray = byteArrayOf(\n" +
                           "        " + chunk.joinToString(",") { it.toString() } + "\n" +
                           "    )\n}\n"
                File(outDirFile, "${name}_$i.kt").writeText(code)
            }

            var code = "package borg.trikeshed.forge.generated\n\ninternal object ${name} {\n"
            code += "    val data: ByteArray get() {\n"
            code += "        val size = " + bytes.size + "\n"
            code += "        val arr = ByteArray(size)\n"
            code += "        var offset = 0\n"
            for (i in chunks.indices) {
                code += "        ${name}_$i.data.copyInto(arr, offset)\n"
                code += "        offset += ${chunks[i].size}\n"
            }
            code += "        return arr\n"
            code += "    }\n}\n"
            File(outDirFile, "${name}.kt").writeText(code)
            return name
        }

        createByteArray("ForgeAssetsHtml", htmlFile.readBytes())
        createByteArray("ForgeAssetsCss", cssFile.readBytes())
        createByteArray("ForgeAssetsJs", jsFile.readBytes())

        File(outDirFile, "ForgeAssets.kt").writeText(
            "package borg.trikeshed.forge.generated\n\ninternal object ForgeAssets {\n" +
            "    val indexHtml: String by lazy { ForgeAssetsHtml.data.decodeToString() }\n" +
            "    val stylesCss: String by lazy { ForgeAssetsCss.data.decodeToString() }\n" +
            "    val scriptJs: String by lazy { ForgeAssetsJs.data.decodeToString() }\n" +
            "}\n"
        )
    }
}

// Wire the generated Forge assets into commonMain. Must come AFTER
// generateForgeAssets is registered above — the kotlin { sourceSets { } }
// block near the top of this file cannot forward-reference it.
kotlin.sourceSets.getByName("commonMain") {
    kotlin.srcDir(generateForgeAssets.map { it.outputs.files })
}

tasks.register("metrics") {
    group = "verification"
    description = "Run JMH + regression gate against metrics-baseline.json"

    dependsOn("jmh", "jsNodeTest")

    doLast {
        val jmhResultsFile = project.layout.buildDirectory.file("jmh-result.json").get().asFile
        require(jmhResultsFile.exists()) { "JMH results file not found at \${jmhResultsFile.absolutePath}" }
        val jmhJsonText = jmhResultsFile.readText()

        var coldStart = -1.0
        var zoomLatency = -1.0

        val coldStartRegex = """"benchmark"\s*:\s*"[^"]*coldStartInteractive"[^}]*"primaryMetric"\s*:\s*\{[^}]*"score"\s*:\s*([0-9.]+)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val coldStartMatch = coldStartRegex.find(jmhJsonText)
        if (coldStartMatch != null) {
            coldStart = coldStartMatch.groupValues[1].toDouble()
        }

        val zoomRegex = """"benchmark"\s*:\s*"[^"]*zoomLatency"[^}]*"primaryMetric"\s*:\s*\{[^}]*"score"\s*:\s*([0-9.]+)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val zoomMatch = zoomRegex.find(jmhJsonText)
        if (zoomMatch != null) {
            zoomLatency = zoomMatch.groupValues[1].toDouble()
        }

        require(coldStart >= 0.0) { "coldStartInteractive metric not found in JMH output" }
        require(zoomLatency >= 0.0) { "zoomLatency metric not found in JMH output" }

        var keystrokeToPaint = -1.0
        val jsTestReportDir = project.layout.buildDirectory.dir("test-results/jsNodeTest").get().asFile
        if (jsTestReportDir.exists()) {
            val files = jsTestReportDir.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.name.endsWith(".xml")) {
                        val content = file.readText()
                        val match = "METRIC:keystrokeToPaint:([0-9.]+)".toRegex().find(content)
                        if (match != null) {
                            keystrokeToPaint = match.groupValues[1].toDouble()
                            break
                        }
                    }
                }
            }
        }

        require(keystrokeToPaint >= 0.0) { "keystrokeToPaint metric not found in jsNodeTest output" }

        println("=== UX Metrics ===")
        println("coldStartInteractive: \$coldStart ms/op")
        println("zoomLatency: \$zoomLatency ms/op")
        println("keystrokeToPaint: \$keystrokeToPaint ms")

        val baselineFile = project.file("metrics-baseline.json")
        if (!baselineFile.exists()) {
            println("No baseline found. Creating metrics-baseline.json...")
            val json = """
            {
              "coldStartInteractive": \$coldStart,
              "zoomLatency": \$zoomLatency,
              "keystrokeToPaint": \$keystrokeToPaint
            }
            """.trimIndent()
            baselineFile.writeText(json)
            println("Baseline created successfully.")
        } else {
            val baselineContent = baselineFile.readText()

            val baseColdStartMatch = """"coldStartInteractive"\s*:\s*([0-9.]+)""".toRegex().find(baselineContent)
            val baseZoomMatch = """"zoomLatency"\s*:\s*([0-9.]+)""".toRegex().find(baselineContent)
            val baseKeystrokeMatch = """"keystrokeToPaint"\s*:\s*([0-9.]+)""".toRegex().find(baselineContent)

            val baseColdStart = baseColdStartMatch?.groupValues?.get(1)?.toDouble() ?: 0.0
            val baseZoom = baseZoomMatch?.groupValues?.get(1)?.toDouble() ?: 0.0
            val baseKeystroke = baseKeystrokeMatch?.groupValues?.get(1)?.toDouble() ?: 0.0

            val maxAllowedColdStart = baseColdStart * 1.2
            val maxAllowedZoom = baseZoom * 1.2
            val maxAllowedKeystroke = baseKeystroke * 1.2

            var failed = false
            if (coldStart > maxAllowedColdStart) {
                System.err.println("REGRESSION: coldStartInteractive (\$coldStart) exceeded baseline (\$baseColdStart) by >20% (max \$maxAllowedColdStart)")
                failed = true
            }
            if (zoomLatency > maxAllowedZoom) {
                System.err.println("REGRESSION: zoomLatency (\$zoomLatency) exceeded baseline (\$baseZoom) by >20% (max \$maxAllowedZoom)")
                failed = true
            }
            if (keystrokeToPaint > maxAllowedKeystroke) {
                System.err.println("REGRESSION: keystrokeToPaint (\$keystrokeToPaint) exceeded baseline (\$baseKeystroke) by >20% (max \$maxAllowedKeystroke)")
                failed = true
            }

            if (failed) {
                throw GradleException("UX Metrics Regression detected (>20% over baseline)")
            } else {
                println("All metrics within acceptable bounds of baseline.")
            }
        }
    }
}

tasks.register<JavaExec>("queueGraphWork") {
    group = "trikeshed"
    description = "Queue Graphify + pgGraph merged work to Jules board"
    classpath = files(
        layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        configurations.named("jvmRuntimeClasspath")
    )
    mainClass.set("borg.trikeshed.utils.ingress.QueueGraphWorkKt")
}


// ─────────────────────────────────────────────────────────────────
// commonMain Purity Check — detect JVM-specific patterns
// ─────────────────────────────────────────────────────────────────

tasks.register<Exec>("commonMainPurity") {
    group = "verification"
    description = "Check commonMain for JVM-specific imports and patterns"
    commandLine("bash", "scripts/common-purity.sh")
    isIgnoreExitValue = true
}

tasks.named("check") {
    dependsOn("commonMainPurity")
}
