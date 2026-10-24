import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinBrowserJsIr
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.targets.js.ir.WebpackConfigurator

plugins {
    kotlin("multiplatform") version "2.4.21"
    id("com.github.ben-manes.versions") version "0.54.0"
    `maven-publish`
    kotlin("plugin.serialization") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.11.1"
    kotlin("plugin.allopen") version "2.4.21"
    id("org.jetbrains.kotlinx.benchmark") version "0.5.0"
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
val kotlinJsBrowserApps = listOf("forge", "documents", "spacegraph", "graal", "harness", "pages")
// Select the browser apps packaged with the daemon: -PkotlinJsApps=documents,spacegraph.
val kotlinJsApps = providers.gradleProperty("kotlinJsApps").orElse(kotlinJsBrowserApps.joinToString(",")).get()
    .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
require(kotlinJsApps.all { it in kotlinJsBrowserApps }) { "kotlinJsApps must name apps in $kotlinJsBrowserApps" }
val linuxHost = System.getProperty("os.name").lowercase().contains("linux")
val arm64Host = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
val enableLinuxX64Target = (linuxHost && !arm64Host) || providers.gradleProperty("enableLinuxX64").orNull == "true"
val enableLinuxArm64Target = (linuxHost && arm64Host) || providers.gradleProperty("enableLinuxArm64").orNull == "true"

// ── Locked versions ───────────────────────────────────────────────────────
// GraalVM CE 25.3.4.1 is the locked runtime (matches the JDK toolchain's bundled Truffle/JVMCI); JDK 25 toolchain.
val graalVersion = "25.3.4.1"
val requestFactoryVersion = "2.13.0"

extra["versions.kotlinx-coroutines-core"] = "1.11.0"
extra["versions.kotlinx-coroutines-test"] = "1.11.0"
extra["versions.kotlinx-datetime"] = "0.8.0-0.6.x-compat"
extra["versions.kotlinx-serialization"] = "1.11.0"

val coroutinesVersion = extra["versions.kotlinx-coroutines-core"] as String
val coroutinesTestVersion = extra["versions.kotlinx-coroutines-test"] as String
val datetimeVersion = extra["versions.kotlinx-datetime"] as String
val serializationVersion = extra["versions.kotlinx-serialization"] as String

// Confix owns formats on every target; reject direct and transitive format runtimes.
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlinx" && listOf("json", "cbor").any { format ->
                val module = "kotlinx-serialization-$format"
                requested.name == module || requested.name.startsWith("$module-")
            }) {
                throw GradleException("Confix owns formats on every target; forbidden dependency: $requested")
            }
        }
    }
}


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
                    useChromeHeadless()
                }
            }
        }
        val jsMainCompilation = compilations.getByName("main")
        // KGP creates webpack tasks automatically only for main; app compilations use the same configurator.
        val webpack = WebpackConfigurator((this as KotlinJsIrTarget).browser as KotlinBrowserJsIr)
        webpack.configureBuild {
            mainOutputFileName.set("${compilation.name}.js")
        }
        for (app in kotlinJsBrowserApps + "viewserver") {
            val application = compilations.create(app) {
                associateWith(jsMainCompilation)
            }
            binaries.executable(application)
            if (app in kotlinJsBrowserApps) webpack.setupBuild(application)
        }
        val uringBenchmark = compilations.create("uringBenchmark") {
            associateWith(jsMainCompilation)
            defaultSourceSet.kotlin.srcDir("src/jsBenchmark/kotlin")
        }
        binaries.executable(uringBenchmark)
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        browser {
            testTask {
                enabled = enableBrowserTests
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    // WASI preview 1 (wasmtime, node): files through the preopened directories, no sockets, no JS host.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
        binaries.executable()
    }
    // compose-gradle-plugin 1.11.1 treats every JS-IR target as a web application and asks each of its binaries for a
    // link sync task (configureWebApplication.kt:100); a WASI binary has none. Compose web stays on js and wasmJs.
    @Suppress("DEPRECATION")
    project.extensions.getByType<org.jetbrains.compose.ComposeExtension>().extensions
        .getByType<org.jetbrains.compose.web.WebExtension>().targets(targets.getByName("js"), targets.getByName("wasmJs"))

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
                        defFile = project.file("src/posixMain/resources/META-INF/cinterop/posix_spawn.def")
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
                // Ledger head-to-head with the Rust contender (cocaine-rats loom-isam): same events, same files.
                // ./gradlew linkLedgerReleaseExecutableMacos → build/bin/macos/ledgerReleaseExecutable/ledger.kexe
                executable("ledger") {
                    entryPoint = "borg.trikeshed.userspace.benchmark.ledgerBenchmarkMain"
                }
                // ModelMux + Zenith as an MCP server over stdio: McpStdio (commonMain) with posixMain's descriptors under it.
                // ./gradlew linkMcpStdioReleaseExecutableMacos → build/bin/macos/mcpStdioReleaseExecutable/mcpStdio.kexe
                executable("mcpStdio") {
                    entryPoint = "borg.trikeshed.mux.mcpStdioMain"
                }
                // K/N 2.4.20/2.4.21 ship macos_arm64 system caches tagged iOS (LC_BUILD_VERSION platform 2,
                // e.g. CoreFoundationBase); Xcode 26 ld refuses to link them into a macOS binary, the test binary too.
                configureEach {
                    @OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)
                    disableNativeCache(
                        org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion.`2_4_21`,
                        "K/N macos_arm64 system caches are tagged iOS; Xcode 26 ld rejects them",
                    )
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
        //                 defFile = project.file("src/posixMain/resources/META-INF/cinterop/posix_spawn.def")
        //             }
        //         }
        //     }
        // }
    }

    if (enableLinuxX64Target) {
        linuxX64 {
            if (enableNativeSharedLib) {
                binaries.sharedLib { baseName = "trikeshed" }
            }
        }
    }
    if (enableLinuxArm64Target) {
        linuxArm64 {
            if (enableNativeSharedLib) {
                binaries.sharedLib { baseName = "trikeshed" }
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                api("org.jetbrains.kotlinx:atomicfu:0.28.0")
                // Core supplies annotations, descriptors and generated serializer contracts.
                // Confix owns formats on every target; no kotlinx JSON/CBOR frontend.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
                
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")
                // Compose runtime annotations must be visible to every target so the
                // compose compiler plugin (applied globally) doesn't bail on JS/WASM/Native.
                // Full UI deps stay in jvmMain — Compose doesn't publish for macosX64 (target now commented out).
                // implementation(org.jetbrains.compose.ComposePlugin.Dependencies(project).runtime) // REMOVED: breaks macosX64
            }
            // Slab tree restored to the compile path: TODO() is the sanctioned
            // escape hatch (PRELOAD build hygiene); compilation is not bypassed.

        }

        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesTestVersion")
            }
        }

        val jvmMain = getByName("jvmMain") {
            resources.srcDir("src/jvmMain/resources")
            // Default SUMO resources are Merge + Mid-level only.
            resources.srcDir(layout.buildDirectory.dir("generated/sumo/resources"))
            dependencies {
                implementation("org.openjdk.jmh:jmh-core:1.37")
                implementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
                
                implementation("org.ow2.asm:asm:9.7")
                implementation("org.ow2.asm:asm-tree:9.7")

                // TruffleRuby 40.0.0 uses the same Truffle 25.3.4.1 as graalVersion.
                implementation("org.graalvm.polyglot:polyglot:$graalVersion")
                implementation("org.graalvm.polyglot:js-community:$graalVersion")
                implementation("org.graalvm.polyglot:python-community:$graalVersion")
                implementation("org.graalvm.python:python-bouncycastle-support:$graalVersion")
                implementation("dev.truffleruby:truffleruby:40.0.0")
                implementation("org.graalvm.polyglot:llvm-community:$graalVersion")
                implementation("org.graalvm.truffle:truffle-api:$graalVersion")
                implementation("org.gwtproject.web.bindery:requestfactory-server:$requestFactoryVersion")
                api("org.gwtproject.web.bindery:requestfactory-client:$requestFactoryVersion")

                // Kotlin scripting host: `language: "kotlin"` design-doc views compile
                // against the daemon's own classpath and evaluate in its classloader.
                implementation(kotlin("scripting-common"))
                implementation(kotlin("scripting-jvm"))
                implementation(kotlin("scripting-jvm-host"))

                // Tika is loaded from the managed utils/subvm/tika module by TikaRuntime.
                implementation("org.xerial:sqlite-jdbc:3.53.4.0")
                implementation("org.slf4j:slf4j-api:2.0.16")
                // Opt-in compression dictionaries; ISAM framing and all file I/O remain commonMain.
                implementation("com.github.luben:zstd-jni:1.5.7-23")

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
        if (targets.findByName("linuxX64") != null || targets.findByName("linuxArm64") != null) {
            // The default hierarchy template is disabled; connect the shared Linux sources.
            val linuxMain = maybeCreate("linuxMain").apply {
                dependsOn(posixMain)
                // resources/liburing is the liburing source tree buildLiburing compiles, not a klib resource; its man
                // pages hold symlinks to pages the vendored tree omits, which processResources cannot follow.
                resources.exclude("liburing/**")
            }
            val linuxTest = maybeCreate("linuxTest").apply { dependsOn(posixTest) }
            for (target in listOf("linuxX64", "linuxArm64")) {
                findByName("${target}Main")?.dependsOn(linuxMain)
                findByName("${target}Test")?.dependsOn(linuxTest)
            }
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
        // wasmMain: actuals shared by wasmJs and wasmWasi (no JS interop); each target adds its host bindings.
        val wasmMain = maybeCreate("wasmMain").apply { dependsOn(nonPosixMain) }
        findByName("wasmJsMain")?.dependsOn(wasmMain)
        findByName("wasmWasiMain")?.dependsOn(wasmMain)

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
        getByName("jsMain") {
            dependencies {
                // Browser libraries the Kotlin/JS pages bind (were hand-vendored copies under web/vendor).
                implementation(npm("lucide", "0.468.0"))
                implementation(npm("d3-force", "3.0.0"))
                implementation(npm("fflate", "0.8.2"))
            }
        }
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

// The browser bundles are a downstream artifact of commonMain, built on their own schedule: the JVM
// compiles, tests and packages without them (GWT's split between the compiled page and the server that
// hosts it). Only what HOSTS pages consumes them: the daemon and page servers get them as one more jar on
// the staged lib classpath (the AOT classpath is jars only), the Pages publisher reads the staged
// directory, and the manual-browser tests (*_UI=1) add the jar to their own classpath.
// Shared Kotlin -> app compilation -> webpack -> staging -> bundles jar -> hosts.
val stageKotlinJs = tasks.register<Sync>("stageKotlinJs") {
    group = "forge"
    description = "Package the Kotlin/JS browser apps selected by kotlinJsApps."
    for (app in kotlinJsApps) {
        val bundle = tasks.named<KotlinWebpack>("jsBrowser${app.replaceFirstChar(Char::uppercase)}ProductionWebpack")
        from(bundle.flatMap { it.outputDirectory }) { into("web/kotlin/$app") }
    }
    into(layout.buildDirectory.dir("generated/kotlinJs/resources"))
}

val kotlinJsBundlesJar = tasks.register<Jar>("kotlinJsBundlesJar") {
    group = "forge"
    description = "Package the staged browser bundles (web/kotlin/<app>/) as the jar the daemon and page servers serve /kotlin/ from."
    from(stageKotlinJs)
    archiveClassifier.set("web")
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("jvmProcessResources") {
    // Purge what an earlier layout staged here: processedResources must not shadow the bundles jar.
    doFirst { destinationDir.resolve("web/kotlin").deleteRecursively() }
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

val liburingSource = layout.projectDirectory.dir("src/linuxMain/resources/liburing")
val prebuiltLiburing = providers.gradleProperty("uringLiburingBuildDir")
val liburingBuild = layout.dir(prebuiltLiburing.map { project.file(it) })
    .orElse(layout.buildDirectory.dir("native/liburing"))
val validatePrebuiltLiburing = tasks.register("validatePrebuiltLiburing") {
    val prebuilt = prebuiltLiburing.isPresent
    onlyIf { prebuilt }
    val build = liburingBuild
    doLast {
        val required = listOf("src/liburing.a", "src/include/liburing/compat.h", "src/include/liburing/io_uring_version.h")
        val missing = required.filter { !build.get().file(it).asFile.isFile }
        check(missing.isEmpty()) {
            "uringLiburingBuildDir=${build.get().asFile} lacks required Linux build artifacts: ${missing.joinToString()}"
        }
    }
}
val configureLiburing = tasks.register<Exec>("configureLiburing") {
    dependsOn(validatePrebuiltLiburing)
    val prebuilt = prebuiltLiburing.isPresent
    onlyIf { !prebuilt }
    inputs.file(liburingSource.file("configure"))
    inputs.file(liburingSource.file("Makefile.common"))
    outputs.files(liburingBuild.map { it.file("config-host.mak") }, liburingBuild.map { it.file("config-host.h") },
        liburingBuild.map { it.file("src/include/liburing/compat.h") },
        liburingBuild.map { it.file("src/include/liburing/io_uring_version.h") })
    workingDir(liburingBuild)
    environment("CFLAGS", "-O3 -fPIC")
    commandLine("sh", liburingSource.file("configure").asFile.absolutePath, "--use-libc")
    val linux = linuxHost
    val buildDir = liburingBuild.get().asFile
    doFirst {
        check(linux) { "The liburing build requires a Linux host" }
        buildDir.mkdirs()
    }
}
val buildLiburing = tasks.register<Exec>("buildLiburing") {
    dependsOn(configureLiburing)
    val prebuilt = prebuiltLiburing.isPresent
    onlyIf { !prebuilt }
    inputs.dir(liburingSource.dir("src"))
    inputs.file(liburingSource.file("Makefile"))
    inputs.file(liburingSource.file("Makefile.common"))
    outputs.files(liburingBuild.map { it.file("src/liburing.a") }, liburingBuild.map { it.file("src/liburing-ffi.a") })
    commandLine("make", "-C", liburingBuild.get().asFile.absolutePath, "library", "ENABLE_SHARED=0", "-j2")
}

kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    if (name == "linuxX64" || name == "linuxArm64") {
        compilations.getByName("main").cinterops.create("posixSpawn") {
            defFile = project.file("src/posixMain/resources/META-INF/cinterop/posix_spawn.def")
        }
        binaries.executable("uringBenchmark") {
            entryPoint = "borg.trikeshed.userspace.benchmark.main"
        }
        // The canary and the ledger head-to-head, as on macOS: build/bin/<target>/{canary,ledger}ReleaseExecutable/.
        binaries.executable("canary") {
            entryPoint = "borg.trikeshed.canary.main"
        }
        binaries.executable("ledger") {
            entryPoint = "borg.trikeshed.userspace.benchmark.ledgerBenchmarkMain"
        }
        // ModelMux + Zenith over stdio, as on macOS. ./gradlew linkMcpStdioReleaseExecutableLinuxX64
        // → build/bin/linuxX64/mcpStdioReleaseExecutable/mcpStdio.kexe (linkMcpStdioDebugExecutableLinuxX64 links faster).
        binaries.executable("mcpStdio") {
            entryPoint = "borg.trikeshed.mux.mcpStdioMain"
        }
        compilations.getByName("main").cinterops.create("zlinux_uring") {
            defFile = project.file("src/linuxMain/resources/META-INF/cinterop/liburing.def")
            packageName("zlinux_uring")
            compilerOpts(
                "-I${liburingBuild.get().dir("src/include").asFile.absolutePath}",
                "-I${liburingSource.dir("src/include").asFile.absolutePath}",
                "-I${project.rootDir}/src/linuxMain/resources/io_uring_interop",
            )
            extraOpts("-libraryPath", liburingBuild.get().dir("src").asFile.absolutePath)
            tasks.named(interopProcessingTaskName).configure { dependsOn(buildLiburing) }
        }
    }
}

val configureNodeUring = tasks.register<Exec>("configureNodeUring") {
    dependsOn(buildLiburing)
    inputs.dir("src/jsMain/c")
    outputs.file(layout.buildDirectory.file("native/node/CMakeCache.txt"))
    commandLine("cmake", "-S", project.file("src/jsMain/c").absolutePath,
        "-B", layout.buildDirectory.dir("native/node").get().asFile.absolutePath,
        "-DURING_INCLUDE_DIR=${liburingBuild.get().dir("src/include").asFile.absolutePath};${liburingSource.dir("src/include").asFile.absolutePath}",
        "-DURING_LIBRARY=${liburingBuild.get().file("src/liburing.a").asFile.absolutePath}")
}
tasks.register<Exec>("buildNodeUring") {
    dependsOn(configureNodeUring)
    inputs.dir("src/jsMain/c")
    inputs.file(liburingBuild.map { it.file("src/liburing.a") })
    outputs.file(layout.buildDirectory.file("native/node/trikeshed_uring.node"))
    commandLine("cmake", "--build", layout.buildDirectory.dir("native/node").get().asFile.absolutePath, "--parallel", "2")
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
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.instruction=ALL-UNNAMED",
        "--add-exports", "java.base/jdk.internal.classfile.components=ALL-UNNAMED"
    )
    dependsOn("hotswapAgentJar")
}

tasks.named<Test>("jvmTest") {
    // The manual-browser tests (any *_UI=1 gate) serve the compiled bundles from the classpath; no other test
    // reads one, so only an opted-in run depends on the JS build. -PkotlinJsBundles forces it.
    val serveBundles = System.getenv().any { (name, value) -> name.endsWith("_UI") && value == "1" } ||
        providers.gradleProperty("kotlinJsBundles").isPresent
    inputs.property("serveKotlinJsBundles", serveBundles)
    if (serveBundles) {
        dependsOn(kotlinJsBundlesJar)
        classpath += files(kotlinJsBundlesJar)
    }
    // The same canonical-curation contract can explicitly exercise the operator's provider.
    // Keep fixture/live runs distinct in Gradle's up-to-date checks; none of these inputs is a credential.
    listOf("TRIKESHED_CURATION_PROFILE", "TRIKESHED_CURATION_BASE", "TRIKESHED_CURATION_MODEL").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
    val liveCuration = providers.environmentVariable("TRIKESHED_CURATION_PROFILE")
    outputs.upToDateWhen { !liveCuration.isPresent }
    outputs.doNotCacheIf("Live curation requires a new provider response") { liveCuration.isPresent }
    // Boundary tests read sources and build declarations outside the JVM compilation inputs.
    inputs.files(fileTree(projectDir) {
        include("**/*.gradle.kts", "**/*.gradle", "**/*.versions.toml")
        include("**/src/**/kotlin/**/*.kt", "**/src/**/java/**/*.java")
        exclude("**/build/**", "**/node_modules/**", "**/.*/**")
    }).withPropertyName("confixBoundarySources")
}

tasks.register<JavaExec>("btrfs") {
    group = "application"
    description = "Userspace Btrfs: create, file operations, add, balance, compression, native export, and macFUSE mount."
    dependsOn("jvmMainClasses")
    mainClass.set("borg.trikeshed.btrfs.BtrfsCli")
    classpath(kotlin.jvm().compilations.getByName("main").output.classesDirs, layout.buildDirectory.dir("processedResources/jvm/main"), configurations.getByName("jvmRuntimeClasspath"))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
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

tasks.register<JavaExec>("skinnerbox") {
    group = "application"
    description = "Skinnerbox common-Kotlin peer mesh: identity, priced placement, and durable N=3 service."
    dependsOn("jvmMainClasses")
    mainClass.set("borg.trikeshed.skinnerbox.SkinnerboxCli")
    classpath(kotlin.jvm().compilations.getByName("main").output.classesDirs,
        layout.buildDirectory.dir("processedResources/jvm/main"), configurations.getByName("jvmRuntimeClasspath"))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    standardInput = System.`in`
}

tasks.register<Test>("skinnerboxCheck") {
    group = "verification"
    description = "Verify the Skinnerbox mesh, durable recovery, twins, placement, and affected HTTP/storage contracts."
    val jvmTests = tasks.named<Test>("jvmTest")
    dependsOn("jvmTestClasses")
    testClassesDirs = jvmTests.get().testClassesDirs
    classpath = jvmTests.get().classpath
    filter {
        includeTestsMatching("borg.trikeshed.skinnerbox.*")
        includeTestsMatching("borg.trikeshed.htx.HtxReactorElementTest")
        includeTestsMatching("borg.trikeshed.couch.CouchStorage*Test")
        includeTestsMatching("borg.trikeshed.job.CanonicalCborTest")
        includeTestsMatching("borg.trikeshed.job.ContentIdTest")
        includeTestsMatching("borg.trikeshed.util.oroboros.Sha2CasBusTest")
        includeTestsMatching("borg.trikeshed.context.nuid.*")
        includeTestsMatching("borg.trikeshed.userspace.JvmUserspaceChannelBackendTest")
        includeTestsMatching("borg.trikeshed.userspace.FunctionalUringFacadeLifecycleTest")
    }
    timeout.set(Duration.ofSeconds(240))
    outputs.upToDateWhen { false }
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

// Include the configured browser tests for both Kotlin browser targets.
tasks.named("jsTest") {
    dependsOn("jsBrowserTest")
}
tasks.named("wasmJsTest") {
    dependsOn("wasmJsBrowserTest")
}

// Forge widget gallery — print the catalog + blackboard to stdout for JVM sanity checks
tasks.register<JavaExec>("printForgeGallery") {
    group = "forge"
    description = "Print the Forge widget gallery catalog and blackboard view to stdout."
    dependsOn("compileKotlinJvm")
    mainClass.set("borg.trikeshed.forge.gallery.ForgeGalleryPrinterKt")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

// Frozen SUMO: full taxonomy + WordNet noun lexicon as one binary resource, built once.
val sumoFrozenDir = layout.buildDirectory.dir("generated/sumo-frozen")
val freezeSumo = tasks.register<JavaExec>("freezeSumo") {
    group = "data"
    description = "Parse the full SUMO corpus and WordNet noun mapping once into sumo/frozen.bin."
    dependsOn("jvmJar", "sumoFullCorpusJar")
    mainClass.set("borg.trikeshed.ontology.SumoFrozen")
    classpath(tasks.named("jvmJar"), tasks.named("sumoFullCorpusJar"), configurations.getByName("jvmRuntimeClasspath"))
    val out = sumoFrozenDir.map { it.file("sumo/frozen.bin") }
    inputs.file("utils/sumo/sumo-full-corpus.pins")
    inputs.file("utils/sumo/sumo-corpus.pins")
    // The image's format is the code's: re-freeze when the writer or the lexicon parse changes.
    inputs.file("src/jvmMain/kotlin/borg/trikeshed/ontology/SumoFrozen.kt")
    inputs.file("src/jvmMain/kotlin/borg/trikeshed/ontology/SumoCorpus.kt")
    outputs.file(out)
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.absolutePath) })
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

// The frozen SUMO image (full taxonomy + WordNet lexicon + axiom relation) as a jar: the daemon's
// classpath is jars only (AOT rejects directories), and without it SumoCorpus falls back to the
// pinned middle ontology, where e.g. Automobile is not a class.
val sumoFrozenJar = tasks.register<Jar>("sumoFrozenJar") {
    group = "data"
    description = "Package build/generated/sumo-frozen (sumo/frozen.bin) for the daemon classpath."
    dependsOn(freezeSumo)
    from(sumoFrozenDir)
    archiveClassifier.set("sumo-frozen")
}

val stageDaemonLib = tasks.register<Sync>("stageDaemonLib") {
    group = "oroboros"
    description = "Copy the JVM runtime classpath jars into build/staging/lib/ for debug-friendly launchers."
    dependsOn("jvmJar")
    from(configurations.named("jvmRuntimeClasspath"))
    from(sumoFrozenJar)
    from(kotlinJsBundlesJar)
    into(stagingLibDir)
}

// The runtime classpath for scripts/mux and scripts/mcp-stdio, which run the JVM entry points without a Gradle round-trip per call.
// Build it once, offline: ./gradlew --offline muxClasspath -PkotlinJsApps=   → build/mux/classpath.txt
// The build simplification removed this as dead; those two launchers (and test_mux_launcher / test_mcp_launcher) are its callers.
tasks.register("muxClasspath") {
    group = "oroboros"
    description = "Write the JVM runtime classpath to build/mux/classpath.txt for scripts/mux and scripts/mcp-stdio."
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

// `-Pjdwp=5005` attaches a JDWP listener (suspend=n); `-Pjdwp=5005,suspend` waits for the debugger
// before main runs. Replaces the --debug/--suspend flags the old bin/* wrappers parsed.
val jdwpSpec: String? = providers.gradleProperty("jdwp").orNull

fun org.gradle.api.tasks.JavaExec.useStagedJvmClasspath() {
    dependsOn("stageDaemonLib", "compileKotlinJvm", "jvmProcessResources")
    jdwpSpec?.let { spec ->
        val port = spec.substringBefore(',').trim()
        val suspend = if (spec.substringAfter(',', "").trim() == "suspend") "y" else "n"
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=$suspend,address=*:$port")
    }
    // Naked classes and processed resources (web/graal.html, futon.html, META-INF/services, openapi/*:
    // without them the server answers /graal and /futon with 404) plus the staged runtime jars.
    classpath = files("build/classes/kotlin/jvm/main", "build/processedResources/jvm/main") + fileTree(stagingLibDir) { include("*.jar") }
}

// The daemon on the classpath utils/oroboros uses: jvmJar plus the staged runtime jars (which carry the
// browser bundles and the frozen SUMO image). --args passes daemon arguments; -Pjdwp attaches a debugger.
tasks.register<JavaExec>("runOroborosDaemon") {
    group = "oroboros"
    description = "Launch OroborosDaemon on jvmJar + build/staging/lib, as utils/oroboros does."
    dependsOn(stageDaemonLib)
    mainClass.set("borg.trikeshed.daemon.OroborosDaemon")
    classpath(tasks.named("jvmJar"), fileTree(stagingLibDir) { include("*.jar") })
    jvmArgs("-Xmx8g", "--add-modules=jdk.internal.vm.ci", "--enable-native-access=ALL-UNNAMED")
    jdwpSpec?.let { spec ->
        val port = spec.substringBefore(',').trim()
        val suspend = if (spec.substringAfter(',', "").trim() == "suspend") "y" else "n"
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=$suspend,address=*:$port")
    }
    standardInput = System.`in`
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
//   Deploy = generate, commit docs/, push. Pages = branch master, folder /docs; no Actions workflows.
//

val forgePagesStages: Set<String> = providers.gradleProperty("forgePagesStages").orElse("jvm").get()
    .split(',').map(String::trim).filter(String::isNotEmpty).toSet()
require(forgePagesStages.all { it in setOf("jvm", "js", "wasm") }) { "forgePagesStages must be a subset of jvm,js,wasm; got $forgePagesStages" }
require("jvm" in forgePagesStages) { "forgePagesStages must include jvm (the baker)" }

val forgeBundleScripts: List<String> = buildList {
    if ("js" in forgePagesStages) add("./kotlin/forge/forge.js")
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
    from(stageKotlinJs.map { it.destinationDir.resolve("web") })
    // Only the compiled bundle files: the distribution also carries every processed resource
    // (web/, confix/, openapi/, …) and, if a webpack SW plugin were present, its own sw.js.
    val bundleFiles = listOf("*.js", "*.mjs", "*.wasm", "*.LICENSE.txt")
    if ("js" in forgePagesStages) {
        require("forge" in kotlinJsApps) { "forgePagesStages=js requires forge in kotlinJsApps" }
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
        include("*.png")                // the rendered graphs
        include("connections.json")
        include("graph-template.html")
        include("rete-*.html")
        include("demo/**")
        include("dispatch/**")
        include("subvm/**")
    }

    val stageStamp = forgePagesStages.sorted().joinToString("-")
    inputs.property("forgePagesStages", forgePagesStages.sorted())

    val noJekyllFile = layout.projectDirectory.file("docs/.nojekyll").asFile
    doLast {
        val noJekyll = noJekyllFile
        if (!noJekyll.exists()) noJekyll.writeText("\n")
        println("Forge PWA published to docs/ (stages: $stageStamp). Live: utils/oroboros  |  Pages: https://jnorthrup.github.io/TrikeShed/  |  Now: git add docs && git commit && git push")
    }
}

tasks.configureEach {
    if (name == "kmpPartiallyResolvedDependenciesChecker") {
        enabled = false
    }
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
    inputs.files(bundleAllowlist.map { File(resourcesDir, it) }.filter { it.isFile })
    inputs.property("bundleAllowlist", bundleAllowlist)
    outputs.dir(outputDir)

    doLast {
        val outDirFile = outputDir.get().asFile
        outDirFile.deleteRecursively()
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

        File(outDirFile, "ForgeAssets.kt").writeText(
            "package borg.trikeshed.forge.generated\n\ninternal object ForgeAssets {\n" +
            "    val indexHtml: String by lazy { ForgeAssetsHtml.data.decodeToString() }\n" +
            "    val stylesCss: String by lazy { ForgeAssetsCss.data.decodeToString() }\n" +
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


// ─────────────────────────────────────────────────────────────────
// commonMain Purity Check — detect JVM-specific patterns
// ─────────────────────────────────────────────────────────────────

tasks.register("commonMainPurity") {
    group = "verification"
    description = "Check commonMain for JVM-specific imports and patterns"
    // Scope to the Kotlin source dir: resources/ carries vendored data (subvm, tspy,
    // ingest) whose own jvmMain is legitimately JVM code, not commonMain sources.
    val sources = fileTree("src/commonMain/kotlin") { include("**/*.kt") }
    val root = projectDir
    inputs.files(sources)
    doLast {
        val forbidden = Regex("^\\s*import\\s+(java\\.|javax\\.|sun\\.|com\\.sun\\.)")
        val violations = sources.files.sorted().flatMap { source ->
            source.readLines().mapIndexedNotNull { index, line ->
                if (forbidden.containsMatchIn(line)) "${source.relativeTo(root)}:${index + 1}: $line" else null
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

tasks.register("scriptPolicy") {
    group = "verification"
    description = "Report remaining non-Gradle operator scripts as policy violations."
    val root = projectDir
    doLast {
        // `scripts` at the root is the owner's marker file that keeps a scripts/ directory from existing.
        val scriptsDir = File(root, "scripts")
        val violations = (if (scriptsDir.isDirectory) scriptsDir.walkTopDown().filter { it.isFile }.toList() else emptyList()) +
            File(root, "bin").listFiles().orEmpty().filter { f ->
                f.name.endsWith(".sh") || f.name.startsWith("oroboros-") || f.name in setOf("mux", "modelmux-cli", "trikeshed-btrfs")
            }
        val shells = File(root, "build.gradle.kts").readLines().mapIndexedNotNull { index, line ->
            // The vendored liburing `configure` is third-party build plumbing, not an operator script.
            if (Regex("commandLine\\(\"(bash|sh|zsh)\"").containsMatchIn(line) && "liburingSource.file(\"configure\")" !in line)
                "build.gradle.kts:${index + 1}: ${line.trim()}" else null
        }
        val remaining = violations.sorted().map { it.relativeTo(root).path } + shells
        if (remaining.isNotEmpty()) throw GradleException("Remaining script entrypoints:\n" + remaining.joinToString("\n"))
    }
}


// ── SUMO corpus: inert data dependency ────────────────────────────────────
// Middle retains its two pins and resource paths. Full has a separate manifest
// and preserves repository-relative paths under sumo/full. Both share the
// content-addressed Gradle cache; clean never deletes downloaded payloads.
// The middle fetch keeps its optional offline fallback (-PsumoStrict disables
// it). Full is deferred/experimental and only fetched or packaged by explicit
// tasks below. Its fetch always requires every pin. See doc/todo.md.
val sumoPinFile = file("utils/sumo/sumo-corpus.pins")

fun sumoCorpusTask(taskName: String, pinFile: File, resourceDir: String, full: Boolean = false) = tasks.register(taskName) {
    group = "data"
    description = "Fetch + checksum-verify the pinned SUMO SUO-KIF corpus (ontologyportal/sumo)."

    val outDir = layout.buildDirectory.dir(resourceDir)
    val cacheRoot = File(gradle.gradleUserHomeDir, "trikeshed/sumo")
    val strict = full || providers.gradleProperty("sumoStrict").isPresent
    // Read at configuration time: the action must not reach back into the build script (configuration cache).
    val offline = gradle.startParameter.isOffline
    val root = rootDir

    inputs.file(pinFile)
    // Strictness participates in up-to-date checking: flipping it must re-run the
    // verification, otherwise a cached success masks a strict-mode failure.
    inputs.property("sumoStrict", strict)
    outputs.dir(outDir)

    doLast {
        val dest = outDir.get().asFile
        dest.mkdirs()
        val manifest = File(dest, "corpus.pins")
        // Invalidate the completion marker before any validation or copying.
        // A failed rerun cannot leave a stale manifest claiming completeness.
        if (manifest.exists()) check(manifest.delete()) { "Cannot invalidate $manifest" }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        val lines = pinFile.readLines()
        val ref = lines.firstNotNullOfOrNull { line ->
            Regex("""^#\s*ref\s*=\s*([0-9a-f]{40})\s*$""").find(line.trim())?.groupValues?.get(1)
        } ?: error("$pinFile: no `# ref = <40-hex commit sha>` line")

        val pins = lines
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { row ->
                val parts = row.split(Regex("""\s+"""), limit = 2)
                require(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}"))) { "$pinFile: malformed row `$row`" }
                require(parts[1].matches(Regex("[A-Za-z0-9_./-]+\\.(kif|txt)")) &&
                    parts[1].split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                    "$pinFile: invalid repository path `${parts[1]}`"
                }
                parts[0] to parts[1]
            }
        require(pins.isNotEmpty() && pins.map { it.second }.distinct().size == pins.size) { "$pinFile: empty or duplicate pins" }
        if (full) {
            val count = lines.firstNotNullOfOrNull { Regex("""^#\s*files\s*=\s*(\d+)\s*$""").find(it)?.groupValues?.get(1)?.toInt() }
            require(count == pins.size) { "$pinFile: expected $count files, found ${pins.size}" }
        }
        val paths = pins.map { it.second }.toSet()
        dest.walkTopDown().filter { it.isFile && it.relativeTo(dest).invariantSeparatorsPath !in paths }
            .forEach { check(it.delete()) { "Cannot remove stale SUMO resource $it" } }

        var fetched = 0
        var cached = 0
        val missing = mutableListOf<String>()

        for ((expected, repoPath) in pins) {
            val name = repoPath.substringAfterLast('/')
            val cacheFile = File(cacheRoot, "$expected/$name")
            val target = File(dest, repoPath)

            if (!cacheFile.isFile) {
                val url = "https://raw.githubusercontent.com/ontologyportal/sumo/$ref/$repoPath"
                val body = if (offline) null else runCatching { URI(url).toURL().readBytes() }.getOrNull()
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
                            "Upstream moved or the pin is stale. Update $pinFile deliberately."
                    )
                }
                cacheFile.parentFile.mkdirs()
                cacheFile.writeBytes(body)
                fetched++
            } else {
                require(sha256(cacheFile.readBytes()) == expected) { "SUMO cache checksum mismatch: $cacheFile" }
                cached++
            }

            if (target.exists() && sha256(target.readBytes()) == expected) continue
            target.parentFile.mkdirs()
            cacheFile.copyTo(target, overwrite = true)
        }

        if (missing.isNotEmpty()) {
            val what = "SUMO corpus unavailable: ${missing.joinToString("; ")}."
            if (strict) {
                error("$what The requested corpus must be complete.")
            }
            logger.warn("w: $what The middle classifier has only the available declarations.")
        }
        // A full loader never mistakes a partial or stale directory for a complete corpus.
        if (missing.isEmpty()) pinFile.copyTo(manifest, overwrite = true)
        logger.lifecycle(
            "SUMO corpus @ $ref -> ${dest.relativeTo(root)} " +
                "(${pins.size - missing.size}/${pins.size} files; $fetched fetched, $cached cached)"
        )
    }
}

val fetchSumoCorpus = sumoCorpusTask("fetchSumoCorpus", sumoPinFile, "generated/sumo/resources/sumo")
val fetchSumoFullCorpus = sumoCorpusTask("fetchSumoFullCorpus", file("utils/sumo/sumo-full-corpus.pins"), "generated/sumo-full/resources/sumo/full", full = true)

tasks.matching { it.name == "jvmProcessResources" }.configureEach {
    dependsOn(fetchSumoCorpus)
}

// Optional data artifact: never attached to assemble, jvmJar, or a publication.
val sumoFullResources = layout.buildDirectory.dir("generated/sumo-full/resources")
tasks.register<Jar>("sumoFullCorpusJar") {
    group = "data"
    description = "Package the deferred full SUMO data for explicit experimental use."
    dependsOn(fetchSumoFullCorpus)
    from(sumoFullResources)
    archiveClassifier.set("sumo-full")
}

// A separate opt-in corpus suite; ordinary tests neither download nor package full.
val sumoJvm = kotlin.targets.getByName("jvm") as KotlinJvmTarget
val sumoFullCompilation = sumoJvm.compilations.create("sumoFullTest") {
    associateWith(sumoJvm.compilations.getByName("main"))
    defaultSourceSet.dependencies {
        implementation("org.jetbrains.kotlin:kotlin-test-junit5")
        implementation("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    }
}
tasks.register<Test>("sumoFullTest") {
    group = "verification"
    description = "Explicitly verify the deferred full SUMO corpus and its resource boundary."
    dependsOn(fetchSumoFullCorpus)
    testClassesDirs = sumoFullCompilation.output.classesDirs
    classpath = files(sumoFullCompilation.output.allOutputs, sumoFullCompilation.runtimeDependencyFiles, sumoFullResources)
}

tasks.register<Exec>("buildJvmUring") {
    dependsOn(buildLiburing)
    inputs.file("src/jvmMain/c/uring_jni.c")
    inputs.file(liburingBuild.map { it.file("src/liburing.a") })
    outputs.file(layout.buildDirectory.file("native/jvm/libtrikeshed_uring.so"))
    val jdk = System.getProperty("java.home")
    commandLine("cc", "-O3", "-shared", "-fPIC", "-pthread",
        "-I$jdk/include", "-I$jdk/include/linux",
        "-I${liburingBuild.get().dir("src/include").asFile.absolutePath}",
        "-I${liburingSource.dir("src/include").asFile.absolutePath}",
        project.file("src/jvmMain/c/uring_jni.c").absolutePath,
        liburingBuild.get().file("src/liburing.a").asFile.absolutePath,
        "-o", layout.buildDirectory.file("native/jvm/libtrikeshed_uring.so").get().asFile.absolutePath)
    val nativeDir = layout.buildDirectory.dir("native/jvm").get().asFile
    doFirst { nativeDir.mkdirs() }
}

// The column-kernel library behind JvmColumnOperations (ColumnOperations.jvm.kt): the loops of lib/<primitive>p in C, built
// with -O3 so the compiler vectorizes the selection and arithmetic loops, and -ffp-contract=off so sums stay bit-identical
// to the Kotlin kernels. Built where a C compiler exists; elsewhere KotlinColumnOperations answers.
val kernelsLibrary = layout.buildDirectory.file("native/jvm/libtrikeshed_kernels.so")
val hasCCompiler = (System.getenv("PATH") ?: "").split(File.pathSeparator).any { File(it, "cc").canExecute() }
val buildJvmKernels = tasks.register<Exec>("buildJvmKernels") {
    inputs.files("src/nativeInterop/c/trikeshed_kernels.c", "src/nativeInterop/c/trikeshed_kernels.h")
    outputs.file(kernelsLibrary)
    val library = kernelsLibrary.get().asFile
    commandLine("cc", "-O3", "-ffp-contract=off", "-shared", "-fPIC", "-Wall", "-Wextra",
        project.file("src/nativeInterop/c/trikeshed_kernels.c").absolutePath, "-o", library.absolutePath, "-lm")
    doFirst { library.parentFile.mkdirs() }
}
if (hasCCompiler) {
    tasks.named<Test>("jvmTest") {
        dependsOn(buildJvmKernels)
        inputs.file(kernelsLibrary)
        systemProperty("trikeshed.libkernels", kernelsLibrary.get().asFile.absolutePath)
    }
    tasks.matching { it.name.startsWith("jvmBench") && it is JavaExec }.configureEach {
        this as JavaExec
        dependsOn(buildJvmKernels)
        jvmArgs("-Dtrikeshed.libkernels=" + kernelsLibrary.get().asFile.absolutePath)
    }
}

// ── Uring benchmarks through kotlinx-benchmark (JMH on the JVM) ──────────────────────────────────────────────────────
// src/commonBench/kotlin holds the workload; each target compiles it in its own "bench" compilation, associated with
// main. Configuration "main" runs the emulated rings everywhere; "native" runs the kernel ring where one exists
// (linuxX64 kexe, JVM through libtrikeshed_uring.so): `./gradlew <target>BenchBenchmark`, `<target>BenchNativeBenchmark`.
val benchTargetNames = setOf("jvm", "js", "wasmJs", "wasmWasi", "macos", "linuxX64", "linuxArm64")
kotlin {
    targets.matching { it.name in benchTargetNames }.all {
        val main = compilations.getByName("main")
        compilations.create("bench") {
            associateWith(main)
            defaultSourceSet {
                kotlin.srcDir("src/commonBench/kotlin")
                dependencies { implementation("org.jetbrains.kotlinx:kotlinx-benchmark-runtime:0.5.0") }
            }
        }
    }
}

allOpen { annotation("org.openjdk.jmh.annotations.State") }

benchmark {
    configurations {
        named("main") {
            warmups = 5; iterations = 10; iterationTime = 1; iterationTimeUnit = "s"
            param("mode", "emulated")
            exclude("TpchShapeBench")
            exclude("LexicalShapeBench")
            exclude("GraphShapeBench")
        }
        register("native") {
            warmups = 5; iterations = 10; iterationTime = 1; iterationTimeUnit = "s"
            param("mode", "native")
            exclude("TpchShapeBench")
            exclude("LexicalShapeBench")
            exclude("GraphShapeBench")
        }
        // The DuckDB-parity run: TPC-H Q1 and Q6 from SQL text over 1,000,000 generated lineitem rows, on each execution
        // path (closure / generated class on boxed rows; Kotlin / native kernels on a column-major source).
        // `./gradlew jvmBenchTpchBenchmark`
        register("tpch") {
            warmups = 3; iterations = 5; iterationTime = 2; iterationTimeUnit = "s"
            include("TpchShapeBench")
            param("rows", "1000000")
            param("path", "rows", "jit", "dense", "ffi")
        }
        register("lexical") {
            warmups = 3; iterations = 5; iterationTime = 2; iterationTimeUnit = "s"
            include("LexicalShapeBench")
            param("docs", "2557")
            param("k", "1000")
        }
        // The graph shape of S7: a three-pattern basic graph pattern over 100,000 quads, routed and closure paths, and the load.
        // `./gradlew jvmBenchGraphBenchmark`
        register("graph") {
            warmups = 3; iterations = 5; iterationTime = 2; iterationTimeUnit = "s"
            include("GraphShapeBench")
            param("quads", "100000")
        }
    }
    targets { kotlin.targets.matching { it.name in benchTargetNames }.all { register("${this.name}Bench") } }
}

// JMH forks inherit the host VM's options: native access, and the JNI ring module where Linux builds it.
tasks.matching { it.name.startsWith("jvmBench") && it is JavaExec }.configureEach {
    this as JavaExec
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.library.path=" + layout.buildDirectory.dir("native/jvm").get().asFile.absolutePath)
}
