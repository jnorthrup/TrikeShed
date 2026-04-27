import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.io.File

plugins {
    kotlin("multiplatform") version "2.4.0-Beta1"
}

group = "org.bereft"
version = "1.0"

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    google()
    maven("https://www.jitpack.io")
}

val specFiles = mapOf(
    "cmc"  to layout.projectDirectory.file("../cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml"),
    "krak" to layout.projectDirectory.file("../krak/rest-api/openapi/kraken.openapi.yaml"),
    "rhood" to layout.projectDirectory.file("../rhood/robinhood.openapi.yaml"),
)

// ── self-hosting: run the generator from existing JVM classes ─────────────────

// Step 2: run the generator using java -cp (no separate JAR packaging needed)
val codegenClassesDir = layout.buildDirectory.dir("classes/kotlin/jvm/main")

// ── per-spec generation tasks ─────────────────────────────────────────────────

specFiles.forEach { (name, specFile) ->
    val outputDir = layout.projectDirectory.dir("../${name}-generated/src/generated/kotlin")

    tasks.register<Exec>("openApiGenerate${name.replaceFirstChar { it.uppercase() }}") {
        group = "openapi-codegen"
        description = "Generates Kotlin client+server sources from the $name OpenAPI spec."
        dependsOn("compileKotlinJvm")

        val classes = codegenClassesDir.get().asFile
        val spec = specFile.asFile
        val out = outputDir.asFile

        // build classpath: compiled openapi classes + all runtime deps
        val runtimeFiles = configurations.getByName("jvmRuntimeClasspath").files
        val cpSeparator = File.pathSeparator
        val fullClasspath = (listOf(classes.absolutePath) + runtimeFiles.map { it.absolutePath })
            .joinToString(cpSeparator)

        commandLine("java", "-cp", fullClasspath, "borg.trikeshed.openapi.GenerateSourcesKt",
            "--spec", spec.absolutePath,
            "--target", name,
            "--output", out.absolutePath,
            "--sides", "client,server",
        )

        doLast {
            println("Generated: $out")
        }
    }
}
kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs = listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
            "-Xexpect-actual-classes",
        )
    }

    jvmToolchain(21)

    jvm()

    js(IR) {
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                val firefoxBin = project.file("/Applications/Firefox.app/Contents/MacOS/firefox")
                if (firefoxBin.exists()) {
                    useKarma {
                        useFirefox()
                    }
                } else {
                    useKarma {
                        useChromeHeadless()
                    }
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                api("org.bereft:TrikeShed:1.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
