import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile

plugins {
    kotlin("multiplatform") version "2.4.20"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
    kotlin("plugin.serialization") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
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
// GraalVM CE 25.3.4.1 is the locked runtime (matches the JDK toolchain's bundled Truffle/JVMCI); JDK 25 toolchain.
val graalVersion = "25.3.4.1"

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

    // ── Host-detected native targets — ACTIVE PLATFORM ONLY ─────────────────
    // No mobile shit: ios/watchos/tvos removed (dead targets that only produced
    // "cannot run on the current host" noise and slowed configuration). The
    // active platform is this host; mingwX64 stays property-gated for CI.
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
    } else if (providers.gradleProperty("enableMingwX64").orNull == "true") {
        mingwX64("mingwX64") {
            compilations.getByName("main") {
                cinterops {
                }
            }
        }
    }

    if (isMac) {
        macosArm64("macos") {
            compilations.getByName("main") {
                cinterops {
                    val posixSpawn = create("posixSpawn") {
                        defFile = project.file("src/macosMain/resources/META-INF/cinterop/posix_spawn.def")
                    }
                }
            }
            // The POSIX non-Linux canary (Jim, 2026-09-06): one native executable that runs
            // commonMain end to end with no JVM. Several main()s live on the native classpath,
            // so the entry point is named. Link: ./gradlew linkCanaryReleaseExecutableMacos
            // → build/bin/macos/canaryReleaseExecutable/canary.kexe
            binaries {
                executable("canary") {
                    entryPoint = "borg.trikeshed.canary.main"
                }
            }
        }
        // macIntel: nobody cares (Jim, 2026-09-05). The arm64 target above is named
        // "macos", so `macosMain` is ITS default source set and macosX64Main cannot
        // also depend on it ("can't depend on 'macosMain' which is a default source
        // set for compilation"). Kept as a comment, not deleted.
        // macosX64("macosX64") {
        //     compilations.getByName("main") {
        //         cinterops {
        //             create("posixSpawn") {
        //                 defFile = project.file("src/macosMain/resources/META-INF/cinterop/posix_spawn.def")
        //             }
        //         }
        //     }
        // }
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
                // Full UI deps stay in jvmMain — Compose doesn't publish for macosX64 (target now commented out).
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
            // SUMO SUO-KIF corpus, fetched + checksum-verified by `fetchSumoCorpus`.
            // Classpath layout: sumo/Merge.kif, sumo/Mid-level-ontology.kif
            resources.srcDir(layout.buildDirectory.dir("generated/sumo/resources"))
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
                implementation("org.graalvm.polyglot:llvm-community:$graalVersion")
                implementation("org.graalvm.truffle:truffle-api:$graalVersion")

                // Tika is loaded from the managed utils/subvm/tika module by TikaRuntime.
                implementation("org.xerial:sqlite-jdbc:3.42.0.0")

                // Stanford CoreNLP is deliberately NOT here any more. It has no compile-time
                // reference anywhere in src/ — the vm.corenlp/vm.corenlp.extract legos reach it
                // only through Java.type('edu.stanford.nlp…') inside a guest script — so the only
                // reason it sat on this classpath was that InProcessIsolate could resolve guest
                // classes from the host classpath and nowhere else. It is GPL v3 and stages ~472MB
                // into build/staging/lib on every daemon launch, for a library the deploy's own
                // classes never call.
                //
                // It is now a GUEST MODULE: utils/subvm/corenlp (classes/ + lib/, the same shape as
                // a TrikeShed deploy), resolved by the standalone utils/subvm build and mounted
                // into a per-guest URLClassLoader via VmSpec.module. Install with:
                //     ./gradlew -p utils/subvm installCorenlp
                // Same for Camel (utils/subvm/camel), which was never a dependency here at all.

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
        // Native targets are host- and property-gated above, so their source sets are created
        // only when the target they belong to exists — asked of the target list rather than by
        // re-deriving the gate, which cannot drift. Creating them unconditionally left linuxMain,
        // linuxTest, mingwX64Main and mingwX64Test attached to no compilation on every Mac build,
        // which is the "Unused Kotlin Source Sets" warning.
        if (targets.findByName("linuxX64") != null) {
            // linuxMain is an INTERMEDIATE, not linuxX64's default source set, and this project
            // turns off the default hierarchy template (gradle.properties), so nothing would
            // connect the two on its own: linuxMain would be orphaned even on Linux and its
            // linux_uring exclude would quietly do nothing. Connect it by hand, both ways.
            val linuxMain = maybeCreate("linuxMain").apply {
                dependsOn(posixMain)
                kotlin.exclude("linux_uring/**")
            }
            val linuxTest = maybeCreate("linuxTest").apply { dependsOn(posixTest) }
            getByName("linuxX64Main").dependsOn(linuxMain)
            getByName("linuxX64Test").dependsOn(linuxTest)
        }
        if (targets.findByName("mingwX64") != null) {
            // These two ARE mingwX64's own default source sets, so the target wires the
            // compilation; all that is needed is their place in the hierarchy.
            maybeCreate("mingwX64Main").apply { dependsOn(nativeMain) }
            maybeCreate("mingwX64Test").apply { dependsOn(nativeTest) }
        }
        val macosMain = maybeCreate("macosMain").apply { dependsOn(posixMain) }
        val macosTest = maybeCreate("macosTest").apply { dependsOn(posixTest) }

        val nonPosixMain = maybeCreate("nonPosixMain").apply { dependsOn(commonMain) }
        findByName("jvmMain")?.dependsOn(nonPosixMain)
        findByName("jsMain")?.dependsOn(nonPosixMain)
        findByName("wasmJsMain")?.dependsOn(nonPosixMain)

        // Source Set Hierarchy Documentation:
        // - posixMain: Code shared across posix platforms (macOS, Linux)
        // - macosMain: macOS-specific code
        // - linuxMain: Linux-specific code
        // - appleMain: Apple-platform-specific code (macOS, iOS, etc.)
        // Note: Default KMP hierarchy handles macosX64Main -> macosMain -> appleMain -> nativeMain.
        // We explicitly connect macosMain and linuxMain to posixMain above.
        
        findByName("macosMain")?.dependsOn(posixMain)
        // findByName("macosX64Main")?.dependsOn(getByName("macosMain"))   // macIntel target commented out above
        findByName("macosTest")?.dependsOn(posixTest)
        // findByName("macosX64Test")?.dependsOn(posixTest)
        // linuxMain/linuxTest are connected where they are created, above.
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

val enableLinuxX64Target = System.getProperty("os.name").lowercase().contains("linux")
    || providers.gradleProperty("enableLinuxX64").orNull == "true"

if (enableLinuxX64Target) {
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
        // "started" is what makes a wedged run diagnosable: the last STARTED line
        // with no matching result names the test that is hanging, live, instead of
        // leaving the run a wall of daemon chatter with no test attached to it.
        // Per-test budgets live in src/jvmTest/resources/junit-platform.properties.
        events("started", "passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    jvmArgs(
        "-Xmx3g",
        "--add-exports", "java.base/jdk.internal.classfile=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.components=ALL-UNNAMED"
    )
}

tasks.register<Test>("btrfsStorageCheck") {
    group = "verification"
    description = "Verify userspace Btrfs storage, world replication, ISAM, and fresh-process crash recovery."
    val jvmTests = tasks.named<Test>("jvmTest")
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTests.get().testClassesDirs
    classpath = jvmTests.get().classpath
    filter {
        includeTestsMatching("borg.trikeshed.btrfs.*")
        includeTestsMatching("borg.trikeshed.graal.subvm.BtrfsHostedVmWorldTest")
        includeTestsMatching("borg.trikeshed.graal.subvm.TrikeShedGraalVfs*Test")
        includeTestsMatching("borg.trikeshed.isam.*")
        includeTestsMatching("borg.trikeshed.job.CanonicalCborTest")
        includeTestsMatching("borg.trikeshed.job.ContentIdTest")
        includeTestsMatching("borg.trikeshed.util.oroboros.Sha2CasBusTest")
        includeTestsMatching("borg.trikeshed.userspace.nio.file.spi.DurableStoragePrimitivesTest")
        includeTestsMatching("borg.trikeshed.userspace.JvmUserspaceChannelBackendTest")
        includeTestsMatching("borg.trikeshed.userspace.FunctionalUringFacadeLifecycleTest")
        includeTestsMatching("borg.trikeshed.couch.replicate.*")
        includeTestsMatching("borg.trikeshed.couch.CouchReplicationTest")
        includeTestsMatching("borg.trikeshed.couch.CouchWireRouterAttachmentTest")
    }
    timeout.set(Duration.ofSeconds(120))
    outputs.upToDateWhen { false }
    doFirst {
        systemProperty("trikeshed.storage.classpath", classpath.asPath)
    }
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

// Writes the mux CLI runtime classpath to a file so `bin/mux` can exec java
// directly. A diagnostic you run twenty times while chasing a credential must
// not cost a Gradle daemon round-trip each time — that is the difference
// between a tool an operator reaches for and one they work around.
tasks.register("muxClasspath") {
    group = "oroboros"
    description = "Write the mux CLI runtime classpath to build/mux/classpath.txt for bin/mux."
    dependsOn("jvmJar")
    val outFile = layout.buildDirectory.file("mux/classpath.txt")
    val cp = files(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
    outputs.file(outFile)
    doLast {
        val f = outFile.get().asFile
        f.parentFile.mkdirs()
        f.writeText(cp.joinToString(":") { it.absolutePath })
    }
}

// mux — the keymux/modelmux operator surface. `bin/mux` wraps this; the task is
// here so `./gradlew mux --args="doctor"` works without the wrapper.
tasks.register<JavaExec>("mux") {
    group = "oroboros"
    description = "keymux/modelmux operator surface: keys | models | chat | doctor."
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.mux.MuxCli")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
    // The whole point is the process environment the operator is standing in —
    // a sanitized env would resolve a different key set than their shell does.
    environment(System.getenv())
    standardInput = System.`in`
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

// ── Sub-VM capability harness ──────────────────────────────────────────────
// `subvmHarness` runs the probe suite on the JVM and writes docs/subvm/capabilities-<host>.json +
// capability-matrix.md. `subvmHarnessNative` builds the same harness as a native-image binary
// (build/native/subvm-harness) so the macOS-native and linux-native columns are measured by the
// identical probes; run it inside a linux GraalVM container for the linux row.
tasks.register<JavaExec>("subvmHarness") {
    group = "subvm"
    description = "Measure the sub-VM capability matrix on this JVM (docs/subvm/)."
    useStagedJvmClasspath()
    mainClass.set("borg.trikeshed.graal.subvm.harness.HarnessMain")
    args(listOf("docs/subvm"))
}

tasks.register<JavaExec>("documentFeedHarness") {
    group = "subvm"
    description = "Run managed Tika/Camel/CoreNLP through the document curator and NARS intake."
    useStagedJvmClasspath()
    mainClass.set("borg.trikeshed.graal.subvm.harness.DocumentFeedHarness")
    maxHeapSize = "3g"
}

tasks.register<Exec>("subvmHarnessNative") {
    group = "subvm"
    description = "native-image the sub-VM harness (GraalVM CE; Truffle languages + execution-listener instrument included)."
    dependsOn("stageDaemonLib", "compileKotlinJvm")
    val nativeImage = file(System.getProperty("java.home")).resolve("bin/native-image")
    val outDir = layout.buildDirectory.dir("native").get().asFile
    doFirst {
        outDir.mkdirs()
        if (!nativeImage.exists()) throw GradleException("no native-image at $nativeImage — run Gradle on GraalVM (sdk use java 25.0.2-graalce)")
        val cp = (listOf(file("build/classes/kotlin/jvm/main")) + fileTree(stagingLibDir) { include("*.jar") }.files).joinToString(File.pathSeparator)
        commandLine(
            nativeImage.path, "--no-fallback", "-O1",
            "-H:+UnlockExperimentalVMOptions", "-H:+IncludeAllInstruments",
            "--initialize-at-build-time=kotlin",
            "-cp", cp, "borg.trikeshed.graal.subvm.harness.HarnessMain",
            "-o", outDir.resolve("subvm-harness").path,
        )
    }
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
    dependsOn("jvmProcessResources")
    doFirst {
        val classes = file(if (name == "runOroborosDaemon") "build/live/classes" else "build/classes/kotlin/jvm/main")
        val resources = file("build/processedResources/jvm/main")
        val lib = file(stagingLibDir)
        if (!classes.isDirectory) throw GradleException("missing $classes; run ./gradlew compileKotlinJvm")
        if (!lib.isDirectory) throw GradleException("missing $lib; run ./gradlew stageDaemonLib")
        // Resources (web/graal.html, futon.html, META-INF/services, openapi/*) must be on the
        // classpath or the daemon serves /graal and /futon as 404 — the exact reason the gradle
        // launch looked broken next to the hand-rolled java invocation.
        classpath = files(classes, resources) + fileTree(lib) { include("*.jar") }
    }
}

// Jules CAS bridge tasks removed: Jules is externalized, its in-repo CLI layer deleted.

tasks.register<JavaExec>("portHermesPython") {
    group = "subvm"
    description = "Project Hermes into no-native GraalPy; --args forwards --root/--sleeve/--entry or --console/--command/--columns/--rows."
    mainClass.set("borg.trikeshed.hermes.HermesPythonPortCli")
    dependsOn("jvmJar")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

// Normal launch uses the same required AOT path as the retained AOT task name.
tasks.register<JavaExec>("runOroborosDaemon") {
    group = "oroboros"
    description = "Automatically stage a compatible HotSpot AOT cache and launch OroborosDaemon with AOT required. --args preserves daemon home/repo arguments."
    useDaemonAot()
}

// ── AOT cache (JEP 483 / Leyden, JDK 25) ────────────────────────────────────
// Two hard constraints shape these tasks, both verified against GraalVM CE 25.0.2:
//   1. AOT create/consume REJECTS exploded-directory classpaths ("Error: non-empty directory
//      build/live/classes") — it accepts JAR entries only. So these launch from jvmJar + the
//      staged runtime jars, NOT the hot-swappable build/live/classes the dev launchers use.
//   2. The archive is bound to the EXACT classpath string. stageDaemonAot writes the -cp it used
//      to a sidecar (oroboros.aot.cp); runOroborosDaemonAot reads that same string back, so create
//      and consume use the same ordered entries. AOTMode=on requires an applicable cache.
// Content fingerprints additionally bind runtime binaries, ordered JAR paths, sizes, mtimes and bytes.
// The archive lands at build/staging/oroboros.aot — beside the jars it is bound to, and where the
// build-plane absorber can pick it up so the AOT cache teleports with the install (gap-analysis §7).
val daemonAotCache = layout.buildDirectory.file("staging/oroboros.aot")
val daemonAotCpFile = layout.buildDirectory.file("staging/oroboros.aot.cp")
val daemonAotFingerprintFile = layout.buildDirectory.file("staging/oroboros.aot.fingerprint")

fun daemonAotJava(): File = File(System.getProperty("java.home"), "bin/java").canonicalFile

fun daemonAotHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun daemonAotFingerprint(): String {
    val java = daemonAotJava()
    val runtime = java.parentFile.parentFile
    val runtimeFiles = listOf(java, File(runtime, "release"), File(runtime, "lib/modules")) +
        listOf("lib/server/libjvm.dylib", "lib/server/libjvm.so", "bin/server/jvm.dll")
            .map { File(runtime, it) }.filter { it.isFile }
    val inputs = runtimeFiles + daemonAotClasspath().split(File.pathSeparator).map(::File)
    val fingerprint = buildString {
        appendLine("hotspot-aot-v2")
        appendLine("--add-modules=jdk.internal.vm.ci")
        appendLine(System.getProperty("os.arch"))
        for (name in listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) appendLine("$name=${System.getenv(name).orEmpty()}")
        for (input in inputs) {
            check(input.isFile) { "Missing AOT input: $input" }
            appendLine("${input.canonicalPath}\t${input.length()}\t${input.lastModified()}\t${daemonAotHash(input)}")
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray()).joinToString("") { "%02x".format(it) }
}

fun daemonAotMatches(fingerprint: String): Boolean {
    val aot = daemonAotCache.get().asFile
    return aot.isFile && aot.length() > 0 && runCatching {
        daemonAotCpFile.get().asFile.readText() == daemonAotClasspath() &&
            daemonAotFingerprintFile.get().asFile.readText() == "$fingerprint\n${daemonAotHash(aot)}\n"
    }.getOrDefault(false)
}

fun writeDaemonAotReceipt(target: File, text: String) {
    val temporary = Files.createTempFile(target.parentFile.toPath(), ".aot-", ".tmp")
    try {
        Files.writeString(temporary, text)
        Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}

fun adoptDaemonAot(fingerprint: String): Boolean {
    val aot = daemonAotCache.get().asFile
    val cp = daemonAotClasspath()
    if (!aot.isFile || aot.length() == 0L || daemonAotFingerprintFile.get().asFile.exists() ||
        !daemonAotCpFile.get().asFile.isFile || daemonAotCpFile.get().asFile.readText() != cp) return false
    val archiveHash = daemonAotHash(aot)
    val log = layout.buildDirectory.file("reports/aot/adopt.log").get().asFile
    log.parentFile.mkdirs()
    val process = ProcessBuilder(listOf(daemonAotJava().path, "--add-modules=jdk.internal.vm.ci",
        "-XX:AOTCache=${aot.path}", "-XX:AOTMode=on", "-Xlog:aot=info", "-cp", cp, "-version"))
        .redirectErrorStream(true).redirectOutput(log).start()
    try {
        if (!process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() != 0) {
            logger.lifecycle("[aot] existing cache rejected or acceptance timed out; see $log")
            return false
        }
        val evidence = log.readText()
        if (!evidence.contains("Opened AOT cache ${aot.path}.") ||
            !evidence.contains("Mapped static  region #0") || !evidence.contains("Using AOT-linked classes: true")) {
            logger.lifecycle("[aot] existing cache lacks JVM acceptance evidence; see $log")
            return false
        }
        check(daemonAotFingerprint() == fingerprint && daemonAotHash(aot) == archiveHash) {
            "AOT inputs or archive changed during acceptance; refusing legacy receipt"
        }
        writeDaemonAotReceipt(daemonAotFingerprintFile.get().asFile, "$fingerprint\n$archiveHash\n")
        logger.lifecycle("[aot] adopted JVM-verified existing cache without retraining: $aot")
        return true
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "AOT acceptance JVM did not stop; see $log" }
        }
    }
}

// AOT preparation and verification never publish watched live classes.
// Identical classpath construction for both tasks: app jar, then staged dependency jars in a stable
// sorted order (glob expansion order is not guaranteed; sorting makes create == consume).
fun daemonAotClasspath(): String {
    val jar = tasks.named("jvmJar", org.gradle.jvm.tasks.Jar::class).flatMap { it.archiveFile }.get().asFile
    val libs = (stagingLibDir.get().asFile.listFiles { f -> f.extension == "jar" } ?: emptyArray()).sortedBy { it.name }
    return (listOf(jar) + libs).joinToString(File.pathSeparator) { it.path }
}

// Bounded JVM lifecycle shared by training and cache-consumption verification.
fun aotProbe(flags: List<String>, port: Int, seconds: Long, log: File): String {
    ServerSocket(port).use { }
    // macOS's default temp path exceeds the daemon Unix-domain socket limit.
    val tempRoot = File("/tmp").takeIf { it.isDirectory } ?: File(System.getProperty("java.io.tmpdir"))
    val home = Files.createTempDirectory(tempRoot.toPath(), "ts-aot-").toFile()
    val javaBin = daemonAotJava()
    val command = listOf(javaBin.path, "--add-modules=jdk.internal.vm.ci") + flags + listOf("-Xlog:aot=info", "-cp", daemonAotClasspath(),
        "borg.trikeshed.daemon.OroborosDaemon", "--watch", "--kanban-port", port.toString(),
        "--interval-ms", "86400000", "--agents", "none", home.resolve("forge").path, projectDir.path)
    log.parentFile.mkdirs()
    val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log)
    builder.environment()["HOME"] = home.path
    builder.environment()["TRIKESHED_AOT_DEFAULT"] = "1"
    val process = builder.start()
    try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos()
        var ready = false
        while (process.isAlive && System.nanoTime() < deadline) {
            ready = runCatching {
                client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/health"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200
            }.getOrDefault(false)
            if (ready) break
            Thread.sleep(500)
        }
        check(ready && process.isAlive) { "AOT daemon did not become healthy; see $log" }
        val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/graal/aot"))
            .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "AOT status unavailable: ${response.statusCode()}" }
        logger.lifecycle("[aot] live switches: ${response.body()}")
        if (flags.any { it.startsWith("-XX:AOTCache=") }) {
            val status = JsonSlurper().parseText(response.body()) as Map<*, *>
            val expectedCache = flags.single { it.startsWith("-XX:AOTCache=") }.substringAfter('=')
            check(status["mode"] == "on" && status["cacheInput"] == expectedCache && status["exists"] == true) {
                "AOT daemon did not require the selected cache; see $log"
            }
        }
        Thread.sleep(seconds * 1000)
        check(process.isAlive) { "AOT daemon exited before probe completed; see $log" }
        if (flags.any { it.startsWith("-XX:AOTCacheOutput=") }) {
            val status = JsonSlurper().parseText(response.body()) as Map<*, *>
            if (status["mxBeanRegistered"] == true) {
                val vm = com.sun.tools.attach.VirtualMachine.attach(process.pid().toString())
                val address = try { vm.startLocalManagementAgent() } finally { vm.detach() }
                javax.management.remote.JMXConnectorFactory.connect(javax.management.remote.JMXServiceURL(address)).use { connector ->
                    val ended = connector.mBeanServerConnection.invoke(
                        javax.management.ObjectName("jdk.management:type=HotSpotAOTCache"),
                        "endRecording", emptyArray<Any>(), emptyArray<String>())
                    check(ended == true) { "HotSpot AOT recording did not finalize; see $log" }
                }
                logger.lifecycle("[aot] HotSpotAOTCacheMXBean.endRecording completed")
            } else logger.lifecycle("[aot] AOT MXBean unavailable on this runtime; finalizing at JVM shutdown")
        }
        process.destroy()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "AOT daemon did not finish cache shutdown; see $log" }
        return response.body()
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
        logger.lifecycle("[aot] log: $log; probe home: $home")
    }
}

tasks.register("stageDaemonAot") {
    group = "oroboros"
    description = "Reuse a fingerprint-matched HotSpot AOT cache or train and finalize one automatically. -PaotWarmSeconds=30 -PaotTrainPort=8971."
    dependsOn("jvmJar", "stageDaemonLib", "jvmProcessResources")
    timeout.set(Duration.ofSeconds(180))
    doLast {
        val fingerprint = daemonAotFingerprint()
        if (daemonAotMatches(fingerprint) || adoptDaemonAot(fingerprint)) {
            logger.lifecycle("[aot] reusing compatible cache: ${daemonAotCache.get().asFile} ($fingerprint)")
            return@doLast
        }
        logger.lifecycle("[aot] cache missing or incompatible; training current JAR and runtime ($fingerprint)")
        val warm = providers.gradleProperty("aotWarmSeconds").orElse("30").get().toLong()
        require(warm in 0..30) { "aotWarmSeconds must be 0..30 to retain the three-minute bound" }
        val port = providers.gradleProperty("aotTrainPort").orElse("8971").get().toInt()
        val aot = daemonAotCache.get().asFile
        val pending = File(aot.parentFile, "oroboros.pending.aot")
        aot.parentFile.mkdirs()
        Files.deleteIfExists(pending.toPath())
        val log = layout.buildDirectory.file("reports/aot/train.log").get().asFile
        aotProbe(listOf("-XX:AOTCacheOutput=${pending.path}"), port, warm, log)
        val entries = Regex("Class +CP entries += +([0-9]+)").findAll(log.readText())
            .lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0
        check(pending.isFile && pending.length() > 0 && entries >= 30000) {
            "No validated daemon AOT archive ($entries class CP entries); see $log"
        }
        check(daemonAotFingerprint() == fingerprint) { "AOT inputs changed during training; refusing cache promotion" }
        val archiveHash = daemonAotHash(pending)
        Files.move(pending.toPath(), aot.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        writeDaemonAotReceipt(daemonAotCpFile.get().asFile, daemonAotClasspath())
        writeDaemonAotReceipt(daemonAotFingerprintFile.get().asFile, "$fingerprint\n$archiveHash\n")
        logger.lifecycle("[aot] validated ${aot.length()} bytes, $entries class CP entries: $aot")
    }
}

tasks.register("prepareDaemonAot") {
    group = "oroboros"
    description = "Prepare a compatible required-AOT daemon cache after the current application and runtime jars are staged."
    dependsOn("stageDaemonAot")
}

fun requireDaemonAot(): File {
    val aot = daemonAotCache.get().asFile
    check(daemonAotMatches(daemonAotFingerprint())) {
        "AOT inputs or cache changed after staging; refusing launch without a compatible AOT cache"
    }
    return aot
}

tasks.register<JavaExec>("runOroborosDaemonAot") {
    group = "oroboros"
    description = "Alias launch configuration for the normal required-AOT daemon chain."
    useDaemonAot()
}

fun org.gradle.api.tasks.JavaExec.useDaemonAot() {
    dependsOn("prepareDaemonAot")
    mainClass.set("borg.trikeshed.daemon.OroborosDaemon")
    standardInput = System.`in`
    environment("TRIKESHED_AOT_DEFAULT", "1")
    providers.gradleProperty("daemonArgs").orNull?.let { setArgsString(it) }
    jdwpSpec?.let { spec ->
        val port = spec.substringBefore(',').trim()
        val suspend = if (spec.substringAfter(',', "").trim() == "suspend") "y" else "n"
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=$suspend,address=*:$port")
    }
    doFirst {
        val aot = requireDaemonAot()
        setExecutable(daemonAotJava().path)
        classpath = files(daemonAotClasspath().split(File.pathSeparator))
        jvmArgs("--add-modules=jdk.internal.vm.ci", "-XX:AOTCache=${aot.path}", "-XX:AOTMode=on", "-Xlog:aot=info")
        logger.lifecycle("[aot] required cache: $aot; TRIKESHED_AOT_DEFAULT=1")
    }
}

tasks.register("verifyDaemonAot") {
    group = "verification"
    description = "Boot with required AOT cache, query live AOT switches, and stop the isolated daemon."
    dependsOn("stageDaemonAot")
    timeout.set(Duration.ofSeconds(180))
    doLast {
        val aot = requireDaemonAot()
        val log = layout.buildDirectory.file("reports/aot/verify.log").get().asFile
        aotProbe(listOf("-XX:AOTCache=${aot.path}", "-XX:AOTMode=on"),
            providers.gradleProperty("aotTrainPort").orElse("8971").get().toInt(), 0, log)
        val evidence = log.readText()
        check(evidence.contains("Opened AOT cache ${aot.path}.") &&
            evidence.contains("Mapped static  region #0") &&
            evidence.contains("Using AOT-linked classes: true")) { "JVM did not enable AOT-linked classes; see $log" }
        logger.lifecycle("[aot] AOT-linked classes active and daemon health verified")
    }
}

// Kanban HTTP server for the modelmux CLI.
tasks.register<JavaExec>("runKanbanHttpServerJvm") {
    group = "forge"
    description = "Launch KanbanHttpServerJvm from naked classes + staged lib/. -Pjdwp=5007[,suspend] attaches a debugger."
    mainClass.set("borg.trikeshed.forge.server.KanbanServerMain")
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
val nativeTargetDebt = debtExcludes("native-target-debt.excludes")

// Same ratchet, native side: cut jvm-only commonMain debt from the Kotlin/Native compiles and
// the shared-metadata compile so `gradle build` stays green without touching the JVM path.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
    exclude(nativeTargetDebt)
    inputs.property("nativeDebtExcludes", nativeTargetDebt)
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon>().configureEach {
    exclude(nativeTargetDebt)
    inputs.property("nativeDebtExcludes", nativeTargetDebt)
}

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
    // `*.md` is TOP LEVEL ONLY — a Sync deletes whatever the spec does not produce, so every
    // hand-written subtree has to be named. docs/subvm/ was swept by this task on 2026-09-07
    // (capability-matrix.md and its capabilities json) because nothing here claimed it.
    // A Sync DELETES whatever its spec does not produce, so every hand-written thing in docs/ has to
    // be named here or publishing destroys it. This has already happened twice: docs/subvm/ on
    // 2026-09-07, and before this list was widened the task would also have swept docs/demo/ (96
    // files of guides), the rete/graph pages, the graph .mjs scripts, connections.json and the PNGs.
    // Rule for adding to docs/: if this task does not produce it, add it here in the same commit.
    preserve {
        include("index.html")           // the ForgeApp bake
        include("forge-app.html")       // …kept under its own name too, so index.html can be the shell
        include("surfaces.html")        // the operator-surface index the bake writes
        include(".nojekyll")
        include("*.md")                 // top level only; subtrees below are named explicitly
        include("*.mjs")                // build-graph, check-graph, render-rete-evidence, the model + its test
        include("*.png")                // the rendered graphs
        include("connections.json")
        include("graph-template.html")
        include("rete-*.html")
        include("demo/**")
        include("dispatch/**")
        include("subvm/**")
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
    dependsOn("hotswapAgentJar")
}


tasks.register<Jar>("hotswapAgentJar") {
    group = "build"
    description = "Package HotSwapAgent as a javaagent"
    dependsOn("compileKotlinJvm")
    
    archiveFileName.set("hotswap-agent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    
    from(layout.buildDirectory.dir("classes/kotlin/jvm/main")) {
        include("borg/trikeshed/daemon/HotSwapAgent*.class")
    }
    
    manifest {
        attributes(
            "Premain-Class" to "borg.trikeshed.daemon.HotSwapAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true"
        )
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

    // Common resources baked for every target (CommonResources layer 1). Keys are bundle paths under
    // src/commonMain/resources; missing files are skipped so the allowlist can name future assets.
    val bundleAllowlist = listOf(
        "confix/job-nexus.schema.json",
        "openapi/htx-general.openapi.yaml",
        "openapi/forge-host.openapi.yaml",
        // Immutable WikiSkill trainer 1A. Keep every asset in the common bundle so
        // JVM, browser, Worker, and native evaluate the same bytes.
        "hermes/wiki-trainer/1A/manifest.json",
        "hermes/wiki-trainer/1A/raw/train-explicit-cause-pass.md",
        "hermes/wiki-trainer/1A/raw/train-cooccurrence-fail.md",
        "hermes/wiki-trainer/1A/raw/validation-if-then-pass.md",
        "hermes/wiki-trainer/1A/raw/validation-reversed-cause-fail.md",
        "hermes/wiki-trainer/1A/nlp/dependencies.jsonl",
        "hermes/wiki-trainer/1A/translation/round-trips.jsonl",
        "hermes/wiki-trainer/1A/nars/causal-decisions.jsonl",
        "hermes/wiki-trainer/1A/analysis/performance-watermark.json",
        "hermes/wiki-trainer/1A/deliverable/oroboros-actual-to-greenfield.json",
        "hermes/wiki-trainer/1A/wiki/index.md",
        "hermes/wiki-trainer/1A/wiki/patterns/grounded-causal-link.md",
        "hermes/wiki-trainer/1A/wiki/logs.md",
        "hermes/wiki-trainer/1A/wiki/skill-impact.md",
        "hermes/wiki-trainer/1A/candidate/grounded-causal-link/SKILL.md",
        "hermes/wiki-trainer/1A/candidate/grounded-causal-link/PURPOSE.md",
        "hermes/wiki-trainer/1A/validation/expected-results.json",
    )
    val resourcesDir = file("src/commonMain/resources")
    inputs.file(htmlFile)
    inputs.file(cssFile)
    inputs.file(jsFile)
    inputs.files(bundleAllowlist.map { File(resourcesDir, it) }.filter { it.isFile })
    inputs.property("bundleAllowlist", bundleAllowlist)
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

        val baked = bundleAllowlist.mapIndexedNotNull { i, key ->
            val f = File(resourcesDir, key)
            if (!f.isFile) null else key to createByteArray("ForgeResource_$i", f.readBytes())
        }
        File(outDirFile, "ForgeResourceBundle.kt").writeText(
            "package borg.trikeshed.forge.generated\n\n" +
            "/** Common resources baked by generateForgeAssets (CommonResources layer 1). */\n" +
            "internal object ForgeResourceBundle {\n" +
            "    val map: Map<String, ByteArray> by lazy { mapOf(\n" +
            baked.joinToString("") { (k, obj) -> "        \"$k\" to $obj.data,\n" } +
            "    ) }\n}\n"
        )
    }
}

// Keep the JS/wasm distributions to what the browser needs: the dictionary (6.6 MB), the dead
// bin/run.cmd and the vestigial shell/ are not web assets (CommonResources bakes what they need).
listOf("jsProcessResources", "wasmJsProcessResources").forEach { name ->
    tasks.matching { it.name == name }.configureEach { (this as org.gradle.language.jvm.tasks.ProcessResources).exclude("nlp/**", "bin/**", "shell/**") }
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
    description = "Queue Graphify + pgGraph merged work to the kanban board"
    classpath = files(
        layout.buildDirectory.dir("classes/kotlin/jvm/main"),
        configurations.named("jvmRuntimeClasspath")
    )
    mainClass.set("borg.trikeshed.utils.ingress.QueueGraphWorkKt")
}


// ─────────────────────────────────────────────────────────────────
// commonMain Purity Check — detect JVM-specific patterns
// ─────────────────────────────────────────────────────────────────

tasks.register("commonMainPurity") {
    group = "verification"
    description = "Check commonMain for JVM-specific imports and patterns"
    val sources = fileTree("src/commonMain") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val forbidden = Regex("^\\s*import\\s+(java\\.|javax\\.|sun\\.|com\\.sun\\.)")
        val violations = sources.files.sorted().flatMap { source ->
            source.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) "${source.relativeTo(projectDir)}:${index + 1}: $line" else null
            }
        }
        if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
    }
}

tasks.named("check") {
    dependsOn("commonMainPurity", "scriptPolicy")
}


tasks.register("hotswapFeed") {
    group = "build"
    description = "Publish compiled JVM files atomically, prune stale files, then advance the live generation."
    dependsOn("jvmMainClasses", "stageDaemonLib", "hotswapAgentJar")

    val buildDir = project.layout.buildDirectory.get().asFile
    val srcDir = File(buildDir, "classes/kotlin/jvm/main")
    val javaDir = File(buildDir, "classes/java/jvmMain")
    val liveDir = File(buildDir, "live")
    val destDir = File(liveDir, "classes")

    doLast {
        check(srcDir.isDirectory) { "Missing compiled Kotlin classes: $srcDir" }
        destDir.mkdirs()
        val retained = mutableSetOf<String>()
        // Publish complete files before advancing the generation watched by HotSwapAgent.
        listOf(srcDir, javaDir).filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { source ->
                val relative = source.relativeTo(root).invariantSeparatorsPath
                retained.add(relative)
                val target = destDir.resolve(relative)
                target.parentFile.mkdirs()
                if (!target.isFile || Files.mismatch(source.toPath(), target.toPath()) != -1L) {
                    val temporary = Files.createTempFile(target.parentFile.toPath(), ".feed-", ".tmp")
                    try {
                        Files.copy(source.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING)
                        Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
            }
        }
        destDir.walkBottomUp().forEach { target ->
            if (target.isFile && target.relativeTo(destDir).invariantSeparatorsPath !in retained) Files.delete(target.toPath())
            else if (target.isDirectory && target != destDir && target.list()?.isEmpty() == true) target.delete()
        }
        val genFile = File(liveDir, ".generation")
        val currentGen = if (genFile.exists()) {
            genFile.readText().trim().toLongOrNull() ?: 0L
        } else {
            0L
        }
        val next = Files.createTempFile(liveDir.toPath(), ".generation-", ".tmp")
        try {
            Files.writeString(next, (currentGen + 1).toString() + "\n")
            Files.move(next, genFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(next)
        }
    }
}

tasks.register("oroborosDoctor") {
    group = "verification"
    description = "Check live daemon health and operator routes; -PdaemonUrl=http://127.0.0.1:8888."
    val base = providers.gradleProperty("daemonUrl").orElse("http://127.0.0.1:8888")
    doLast {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val failures = mutableListOf<String>()
        listOf("/api/health", "/api/board", "/panels", "/graal").forEach { path ->
            val url = base.get().trimEnd('/') + path
            try {
                val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(10)).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.discarding())
                logger.lifecycle("${response.statusCode()} $url")
                if (response.statusCode() != 200) failures.add(url)
            } catch (failure: Exception) {
                failures.add("$url: ${failure.javaClass.simpleName}: ${failure.message.orEmpty()}")
            }
        }
        if (failures.isNotEmpty()) throw GradleException(failures.joinToString("\n"))
    }
}

tasks.register("scriptPolicy") {
    group = "verification"
    description = "Report remaining non-Gradle operator scripts as policy violations."
    doLast {
        val violations = fileTree("scripts").files + fileTree("bin") {
            include("*.sh", "*.cjs", "oroboros-*", "mux", "modelmux-cli", "trikeshed-btrfs")
        }.files
        val shells = file("build.gradle.kts").readLines().mapIndexedNotNull { index, line ->
            if (Regex("commandLine\\(\"(bash|sh|zsh)\"").containsMatchIn(line)) "build.gradle.kts:${index + 1}: ${line.trim()}" else null
        }
        val remaining = violations.sorted().map { it.relativeTo(projectDir).path } + shells
        if (remaining.isNotEmpty()) throw GradleException("Remaining script entrypoints:\n" + remaining.joinToString("\n"))
    }
}


// ── SUMO corpus: inert data dependency ────────────────────────────────────
// SUMO is data, not code: ~1.7MB of SUO-KIF that must never be vendored into
// the tree and never re-downloaded on `clean`. Checksums are pinned in
// gradle/sumo-corpus.pins; the payload caches in the Gradle user home keyed by
// content hash and lands in generated jvmMain resources under `sumo/`.
//
// Offline-graceful by design: a missing network with a cold cache logs a
// warning and produces an empty corpus dir so `jvmMainClasses` stays green.
// SumoOntology's hardcoded upper spine is the fallback. Pass -PsumoStrict to
// turn that warning into a build failure (use in release/bake pipelines).
val sumoPinFile = file("gradle/sumo-corpus.pins")
val sumoResourcesRoot = layout.buildDirectory.dir("generated/sumo/resources")

val fetchSumoCorpus = tasks.register("fetchSumoCorpus") {
    group = "data"
    description = "Fetch + checksum-verify the pinned SUMO SUO-KIF corpus (ontologyportal/sumo)."

    val pinFile = sumoPinFile
    val outDir = sumoResourcesRoot.map { it.dir("sumo") }
    val cacheRoot = File(gradle.gradleUserHomeDir, "trikeshed/sumo")
    val strict = providers.gradleProperty("sumoStrict").isPresent

    inputs.file(pinFile)
    // Strictness participates in up-to-date checking: flipping it must re-run the
    // verification, otherwise a cached success masks a strict-mode failure.
    inputs.property("sumoStrict", strict)
    outputs.dir(outDir)

    doLast {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        val lines = pinFile.readLines()
        val ref = lines.firstNotNullOfOrNull { line ->
            Regex("""^#\s*ref\s*=\s*([0-9a-f]{40})\s*$""").find(line.trim())?.groupValues?.get(1)
        } ?: error("gradle/sumo-corpus.pins: no `# ref = <40-hex commit sha>` line")

        val pins = lines
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { row ->
                val parts = row.split(Regex("""\s+"""), limit = 2)
                require(parts.size == 2) { "gradle/sumo-corpus.pins: malformed row `$row`" }
                parts[0] to parts[1]
            }

        val dest = outDir.get().asFile
        dest.mkdirs()

        var fetched = 0
        var cached = 0
        val missing = mutableListOf<String>()

        for ((expected, repoPath) in pins) {
            val name = repoPath.substringAfterLast('/')
            val cacheFile = File(cacheRoot, "$expected/$name")
            val target = File(dest, name)

            if (!cacheFile.isFile) {
                val url = "https://raw.githubusercontent.com/ontologyportal/sumo/$ref/$repoPath"
                val body = runCatching { URI(url).toURL().readBytes() }.getOrNull()
                if (body == null) {
                    missing += "$repoPath (download failed, cold cache)"
                    continue
                }
                val actual = sha256(body)
                if (actual != expected) {
                    error(
                        "SUMO pin mismatch for $repoPath at ref $ref\n" +
                            "  expected $expected\n" +
                            "  actual   $actual\n" +
                            "Upstream moved or the pin is stale. Update gradle/sumo-corpus.pins deliberately."
                    )
                }
                cacheFile.parentFile.mkdirs()
                cacheFile.writeBytes(body)
                fetched++
            } else {
                cached++
            }

            if (target.exists() && sha256(target.readBytes()) == expected) continue
            cacheFile.copyTo(target, overwrite = true)
        }

        if (missing.isNotEmpty()) {
            val what = "SUMO corpus unavailable: ${missing.joinToString("; ")}."
            if (strict) {
                error("$what -PsumoStrict is set, so this is fatal rather than a fallback.")
            }
            logger.warn("w: $what Falling back to SumoOntology's hardcoded upper spine.")
        }
        logger.lifecycle(
            "SUMO corpus @ $ref -> ${dest.relativeTo(rootDir)} " +
                "(${pins.size - missing.size}/${pins.size} files; $fetched fetched, $cached cached)"
        )
    }
}

tasks.matching { it.name == "jvmProcessResources" }.configureEach {
    dependsOn(fetchSumoCorpus)
}

// Curator state model — read Hermes' .usage.json and report where the
// AttentionEconomy floor crossings disagree with the 30/90 wall clock.
// Read-only: proposes nothing, writes nothing, touches no live skill.
tasks.register<JavaExec>("curatorStateModel") {
    group = "oroboros"
    description = "Model Hermes curation state as AttentionEconomy floor crossings; report divergences."
    dependsOn("compileKotlinJvm")
    mainClass.set("borg.trikeshed.narsese.CuratorStateModelCli")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
    providers.gradleProperty("profileDir").orNull?.let { args(it) }
    providers.gradleProperty("daysAhead").orNull?.let { args(it) }
}

// ── The document surface's bundle (Forge genesis, Cut D) ────────────────────
// GWT-style: page logic is commonMain Kotlin compiled to the browser bundle. This stages that
// bundle beside the JVM resources so the daemon serves it at /kotlin/TrikeShed.js for
// /documents. It is not part of hotswapFeed (webpack is a separate step); run it when the
// bundle's sources change: ./gradlew stageKotlinJs
tasks.register<Copy>("stageKotlinJs") {
    group = "forge"
    description = "Stage build/kotlin-webpack/js/productionExecutable/TrikeShed.js as web/kotlin/TrikeShed.js under the JVM resources."
    dependsOn("jsBrowserProductionWebpack")
    from(project.layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) { include("TrikeShed.js") }
    into(project.layout.buildDirectory.dir("processedResources/jvm/main/web/kotlin"))
}
// The staged bundle lands in a directory other JVM tasks read; Gradle wants that ordering said.
tasks.matching { it.name == "jvmJar" || it.name == "jvmTest" || it.name == "stageDaemonLib" }.configureEach { mustRunAfter("stageKotlinJs") }

tasks.register<Exec>("spacegraphBrowserCheck") {
    group = "verification"
    description = "Isolated SpaceGraph provider, interaction and screenshot checks against the local server."
    timeout.set(Duration.ofSeconds(180))
    commandLine(providers.gradleProperty("browserNode").orElse("node").get(), "src/jvmTest/js/spacegraph-browser.check.cjs")
    providers.gradleProperty("browserModules").orNull?.let { environment("NODE_PATH", it) }
    environment("SPACEGRAPH_BASE_URL", providers.gradleProperty("spacegraphBaseUrl").orElse("http://127.0.0.1:8888").get())
    environment("SPACEGRAPH_LOCAL_ASSETS", providers.gradleProperty("spacegraphLocalAssets").orElse("false").get())
    environment("SPACEGRAPH_OFFLINE", providers.gradleProperty("spacegraphOffline").orElse("false").get())
    environment("SPACEGRAPH_LIVE", providers.gradleProperty("spacegraphLive").orElse("false").get())
}

tasks.register<Exec>("landscapeCheck") {
    group = "verification"
    description = "Check shared landscape selection, layout, and Shake contracts."
    timeout.set(Duration.ofSeconds(180))
    commandLine(providers.gradleProperty("browserNode").orElse("node").get(), "--test", "src/jvmTest/js/landscape.test.cjs")
}

tasks.register<Exec>("installSpacegraphDependencies") {
    group = "forge"
    workingDir("design/spacegraph7-port")
    commandLine(providers.gradleProperty("browserNpm").orElse("npm").get(), "ci", "--ignore-scripts", "--no-audit", "--no-fund")
    inputs.files("design/spacegraph7-port/package.json", "design/spacegraph7-port/package-lock.json")
    outputs.dir("design/spacegraph7-port/node_modules")
    timeout.set(Duration.ofSeconds(180))
}
tasks.register<Exec>("bundleSpacegraph") {
    group = "forge"
    dependsOn("installSpacegraphDependencies")
    commandLine(providers.gradleProperty("browserNode").orElse("node").get(), "design/spacegraph7-port/node_modules/esbuild/bin/esbuild",
        "src/commonMain/resources/web/narchy/spacegraph/SpatialWorkspace.mjs", "--bundle", "--format=iife", "--minify",
        "--supported:template-literal=false", "--legal-comments=inline", "--outfile=src/commonMain/resources/web/spacegraph-shadow.js")
    inputs.dir("src/commonMain/resources/web/narchy/spacegraph")
    outputs.file("src/commonMain/resources/web/spacegraph-shadow.js")
    timeout.set(Duration.ofSeconds(180))
}
tasks.matching { it.name.endsWith("ProcessResources") || it.name == "generateForgeAssets" }.configureEach {
    mustRunAfter("bundleSpacegraph")
}
