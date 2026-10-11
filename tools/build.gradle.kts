import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
}

// Entry points and tool-only logic of the cocaine-rats loom binaries; the library is the root project.
// Native: ./gradlew :tools:linkLoomRegistryReleaseExecutableMacos → tools/build/bin/macos/loomRegistryReleaseExecutable/loom-registry.kexe
// JVM: ./gradlew :tools:jvmRun --args="loom-registry verify <dir>" (the first argument names the Rust binary)

val hostOs = System.getProperty("os.name").lowercase()
val linuxHost = hostOs.contains("linux")
val arm64Host = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
val enableLinuxX64Target = (linuxHost && !arm64Host) || providers.gradleProperty("enableLinuxX64").orNull == "true"
val enableLinuxArm64Target = (linuxHost && arm64Host) || providers.gradleProperty("enableLinuxArm64").orNull == "true"

/** One native executable per Rust binary: Gradle binary name to Rust binary name, entry `borg.trikeshed.loom.<binary>Main`. */
val executables = mapOf(
    "loomMesh" to "loom-mesh", "loomRegistry" to "loom-registry", "loomKoboldStage" to "loom-kobold-stage", "loomLease" to "loom-lease",
)

kotlin {
    jvmToolchain(25)
    jvm {
        @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
        mainRun { mainClass.set("borg.trikeshed.loom.LoomToolsJvmKt") }
    }
    if (hostOs.contains("mac")) macosArm64("macos") {
        binaries.configureEach {
            // K/N 2.4.20/2.4.21 ship macos_arm64 system caches tagged iOS; Xcode 26 ld refuses them (root canary, ledger).
            @OptIn(KotlinNativeCacheApi::class)
            disableNativeCache(DisableCacheInKotlinVersion.`2_4_21`, "K/N macos_arm64 system caches are tagged iOS; Xcode 26 ld rejects them")
        }
    }
    if (enableLinuxX64Target) linuxX64()
    if (enableLinuxArm64Target) linuxArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries {
            for ((binary, name) in executables) executable(binary) {
                entryPoint = "borg.trikeshed.loom.${binary}Main"
                baseName = name
            }
        }
    }

    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(project(":"))
                // The keeper's entry runs the library's suspend HTTP client.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.extra["versions.kotlinx-coroutines-core"]}")
            }
        }
        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val nativeMain = maybeCreate("nativeMain").apply { dependsOn(commonMain) }
        val nativeTest = maybeCreate("nativeTest").apply { dependsOn(commonTest) }
        targets.withType<KotlinNativeTarget>().forEach { target ->
            getByName("${target.name}Main").dependsOn(nativeMain)
            getByName("${target.name}Test").dependsOn(nativeTest)
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}

// The root's jvmJar runs stageKotlinJs and fetchSumoCorpus; take its class-directory variant instead.
for (classpath in listOf("jvmCompileClasspath", "jvmRuntimeClasspath", "jvmTestCompileClasspath", "jvmTestRuntimeClasspath")) {
    configurations.named(classpath) {
        attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.CLASSES))
    }
}
