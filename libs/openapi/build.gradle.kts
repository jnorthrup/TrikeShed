import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
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

    js {
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
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-core").get()}")
                api(project(":"))
                // confix lives in the root TrikeShed source (src/commonMain); openapi
                // accesses it transitively via the TrikeShed published API. No extra
                // dependency needed here.
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-test").get()}")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}

// Register generation tasks after project evaluation.
// Using JavaExec with classpath from the resolved jvmJar + runtime classpath.
afterEvaluate {
    val jvmMainClasspath = configurations.getByName("jvmRuntimeClasspath")

    specFiles.forEach { (name, specFile) ->
        val outputDir = layout.projectDirectory.dir("../${name}-generated/src/generated/kotlin")

        tasks.register<JavaExec>("openApiGenerate${name.replaceFirstChar { it.uppercase() }}") {
            group = "openapi-codegen"
            description = "Generates Kotlin client+server sources from the $name OpenAPI spec."
            dependsOn("jvmMainClasses")

            classpath = jvmMainClasspath + files(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
            mainClass.set("borg.trikeshed.openapi.GenerateSourcesKt")
            args(
                "--spec", specFile.asFile.absolutePath,
                "--target", name,
                "--output", outputDir.asFile.absolutePath,
                "--sides", "client,server",
            )

            doLast {
                println("Generated: ${outputDir.asFile}")
            }
        }
    }
}
