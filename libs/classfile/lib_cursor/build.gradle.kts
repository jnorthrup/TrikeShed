import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("application")
    id("me.champeau.jmh") version "0.7.2"
    id("java-library")
}

group = "org.xvm"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

// JVM Toolchain for consistent JVM target
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Explicitly configure JavaCompile to use JVM 21
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

// javatools on classpath so KSP resolver can see org.xvm.asm.* types
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Kotlin compiler args for JEP 484 ClassFile API access
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf(
            "--add-exports=java.base/jdk.internal.classfile=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.constantpool=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.attribute=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.classfile.instruction=ALL-UNNAMED"
        ))
    }
}

// javatools on classpath so KSP resolver can see org.xvm.asm.* types
// Optional: only add if the JAR exists
val javatoolsJar = layout.projectDirectory.file("../javatools/build/libs/javatools-0.4.4-SNAPSHOT.jar")
val hasJavatools = javatoolsJar.asFile.exists()

private class PointcutVmXdkLibDirProvider(
    private val xdkLibDir: Provider<String>
) : CommandLineArgumentProvider {
    @get:Input
    val snapshot: String get() = xdkLibDir.get()

    override fun asArguments(): Iterable<String> = listOf("-DpointcutVm.xdkLibDir=$snapshot")
}

dependencies {
    // TrikeShed JVM artifact from mavenLocal (published by TrikeShed's publishToMavenLocal)
    implementation("org.bereft:TrikeShed-jvm:1.0")
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")

    // Series codec annotations (compile-only; processor removed with lib_cursor_ksp)
    compileOnly("org.xvm:annotations:0.1.0-SNAPSHOT")

    // javatools — compileOnly so KSP can resolve asm types, not shipped in lib_cursor
    compileOnly(files(javatoolsJar))
    // javatools stays off the in-process test runtime classpath; PointcutCmdlineTest launches a child VM
    // with an unpacked javatools image to avoid org.xvm.runtime package sealing conflicts.
    testCompileOnly(files(javatoolsJar))

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testImplementation("org.junit.platform:junit-platform-engine:6.0.3")
    testImplementation("org.junit.platform:junit-platform-launcher:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
    testImplementation(kotlin("test"))
}

val pointcutVmJavatoolsDir = layout.buildDirectory.dir("pointcut-vm/javatools")

val unpackPointcutVmJavatools = tasks.register("unpackPointcutVmJavatools", Sync::class) {
    from({ zipTree(javatoolsJar.asFile) })
    into(pointcutVmJavatoolsDir)
}

val xdkInstallDir = layout.projectDirectory.dir("../xdk/build/install/xdk/lib")
val pointcutVmXdkLibDir = providers.provider {
    val dir = xdkInstallDir.asFile
    if (dir.isDirectory) dir.absolutePath else ""
}

tasks.test {
    useJUnitPlatform()
    failOnNoDiscoveredTests.set(false)
    dependsOn(unpackPointcutVmJavatools)
    gradle.parent?.let { root ->
        dependsOn(root.includedBuild("xdk").task(":installDist"))
        dependsOn(root.includedBuild("manualTests").task(":compileXtc"))
    }
    systemProperty("pointcutVm.javatoolsDir", pointcutVmJavatoolsDir.get().asFile.absolutePath)
    jvmArgumentProviders.add(PointcutVmXdkLibDirProvider(pointcutVmXdkLibDir))
    classpath = files(pointcutVmJavatoolsDir) + classpath
}

val runMacro = tasks.register("runMacro", JavaExec::class) {
    val mainSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("main")
    classpath = mainSourceSet.runtimeClasspath
    mainClass.set("org.xvm.cursor.ToSeriesMacroKt")
    if (project.hasProperty("macroArgs")) {
        args((project.property("macroArgs") as String).split(" "))
    }
}

val runPointcutCmdline = tasks.register("runPointcutCmdline", JavaExec::class) {
    val mainSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("main")
    dependsOn(unpackPointcutVmJavatools, tasks.named("classes"))
    classpath = files(pointcutVmJavatoolsDir) + mainSourceSet.runtimeClasspath
    mainClass.set("org.xvm.cursor.PointcutCmdlineKt")
    if (project.hasProperty("pointcutMode")) {
        args((project.property("pointcutMode") as String).split(" "))
    } else {
        args("xvm")
    }
}

val generateJep483Dump = tasks.register("generateJep483Dump", JavaExec::class) {
    val mainSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("main")
    dependsOn(unpackPointcutVmJavatools, tasks.named("classes"))
    classpath = files(pointcutVmJavatoolsDir) + mainSourceSet.runtimeClasspath
    mainClass.set("org.xvm.cursor.PointcutCmdlineKt")
    args("xvm")

    val dumpDir = layout.buildDirectory.dir("jep483_dumps").get().asFile
    doFirst { dumpDir.mkdirs() }

    jvmArgs(
        "-XX:DumpLoadedClassList=${dumpDir.absolutePath}/aot_classes.lst"
    )
    isIgnoreExitValue = true
}

application {
    mainClass.set("org.xvm.cursor.BlackboardTimeseriesKt")
}

// ── JEP 484 Typedef Classfile Scanner ───────────────────────────────────────
// val runTypedefScan = tasks.register("runTypedefScan", JavaExec::class) {
//     val mainSourceSet = project.extensions.getByType<SourceSetContainer>().getByName("main")
//     dependsOn(tasks.named("classes"))
//     classpath = mainSourceSet.runtimeClasspath
//     mainClass.set("org.xvm.cursor.TypedefClassfileScannerKt")
//     if (project.hasProperty("scanPath")) {
//         args(project.property("scanPath") as String)
//     } else if (project.hasProperty("scanJar")) {
//         args("--jar", project.property("scanJar") as String)
//     }
// }

// ── JMH opt-out via -Pjmh=false ─────────────────────────────────────────────
// val jmhDisabled = providers.gradleProperty("jmh").orNull == "false"
// if (jmhDisabled) {
//     tasks.matching { it.name.startsWith("jmh") }.configureEach { enabled = false }
//     tasks.named("compileJmhKotlin") { enabled = false }
// }