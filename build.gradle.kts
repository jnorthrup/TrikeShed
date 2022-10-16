import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

//val KOTLIN_COMPILER_ARGS = listOf(
//    "-opt-in=kotlin.RequiresOptIn",
//    "-opt-in=kotlin.Experimental",
//
//    // https://docs.gradle.org/nightly/userguide/configuration_cache.html#config_cache:not_yet_implemented:storing_lambdas
//    "-Xsam-conversions=class",
//
//    // Emit JVM type annotations in bytecode
//    "-Xemit-jvm-type-annotations",
//
//    // Enhance not null annotated type parameter's types to definitely not null types (@NotNull T => T & Any)
//    "-Xenhance-type-parameter-types-to-def-not-null",
//
//    // strict (experimental; treat as other supported nullability annotations)
//    "-Xjsr305=strict",
//
//    // When using the IR backend, run lowerings by file in N parallel threads. 0 means use a thread per processor core. Default value is 1
//    "-Xbackend-threads=0",
//
//    // Enable strict mode for some improvements in the type enhancement for loaded Java types based on nullability annotations,including freshly supported reading of the type use annotations from class files. See KT"-45671 for more details",
//    "-Xtype-enhancement-improvements-strict-mode",
//
//    // Use fast implementation on Jar FS. This may speed up compilation time, but currently it's an experimental mode
//    "-Xuse-fast-jar-file-system",
//
//    // Enable experimental context receivers
//    "-Xcontext-receivers",
//
//    // Enable additional compiler checks that might provide verbose diagnostic information for certain errors.
//    "-Xextended-compiler-checks",
//
//    // Enable incremental compilation
//    "-Xenable-incremental-compilation",
//
//    // Enable compatibility changes for generic type inference algorithm
//    "-Xinference-compatibility",
//
//    // Support inferring type arguments based on only self upper bounds of the corresponding type parameters
//    "-Xself-upper-bound-inference",
//
//    // Eliminate builder inference restrictions like allowance of returning type variables of a builder inference call
//    "-Xunrestricted-builder-inference",
//
//    "-Xuse-k2"
//)

plugins {
    kotlin("multiplatform") version "1.7.20"
    id("org.jetbrains.intellij") version "1.9.+" apply false
    id("org.jetbrains.dokka") version "1.7.0"
    id("org.jlleitschuh.gradle.ktlint") version "11.0.0"

    // support kotlinx-datetime
    id("org.jetbrains.kotlin.plugin.serialization") version "1.7.20" apply false

    // support for k2 compiler plugin
    id("org.jetbrains.kotlin.kapt") version "1.7.20" apply false
    id("org.jetbrains.kotlin.plugin.allopen") version "1.7.20"
    id("org.jetbrains.kotlin.plugin.noarg") version "1.7.20"

    // gradle versions update plugin
    id("com.github.ben-manes.versions") version "0.42.0"

}

group = "org.bereft"
version = "1.0"

repositories {
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://mvnrepository.com/artifact/org.jetbrains.kotlinx/")
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
    google()
}

kotlin {
    jvm {
        withJava()
    }

    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    linuxX64 { // Replace with a target you need.
        compilations.getByName("main") {
//            val myInterop by cinterops.creating {
//                // Def-file describing the native API.
//                // The default path is src/nativeInterop/cinterop/<interop-name>.def
//                defFile(project.file("def-file.def"))
//
//                // Package to place the Kotlin API generated.
//                packageName("org.sample")
//
//                // Options to be passed to compiler by cinterop tool.
//                compilerOpts("-Ipath/to/headers")
//
//                // Directories for header search (an analogue of the -I<path> compiler option).
//                includeDirs.allHeaders("path1", "path2")
//
//                // A shortcut for includeDirs.allHeaders.
//                includeDirs("include/directory", "another/directory")
//            }
//            val anotherInterop by cinterops.creating { /* ... */ }
        }
    }

    sourceSets {

        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
            }
        }

        val commonTest by getting {
            //bring in the dependencies from commonMain
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }

        val nativeMain by getting {

            dependencies {
                //native coroutines
            }
        }

        val nativeTest by getting {
            //bring in the dependencies from nativeMain
            dependencies {
            }
        }
        val linuxX64Main  by getting { dependsOn(nativeMain) }
        val linuxX64Test  by getting { dependsOn(nativeTest) }


        val jvmMain by getting {
            dependencies {
                //datetime
                implementation("org.jetbrains.kotlinx:kotlinx-datetime-jvm:0.4.0")

                //coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.6.2")

                //serialization
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.3.0")

            }
        }

        val jvmTest by getting{
            //bring in the dependencies from jvmMain
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
    }
//    tasks.withType<KotlinCompile>().configureEach {
//    kotlinOptions.freeCompilerArgs += KOTLIN_COMPILER_ARGS
//}
}