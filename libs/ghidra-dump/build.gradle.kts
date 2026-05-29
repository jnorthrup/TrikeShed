plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

val ghidraHome: String = providers.gradleProperty("ghidraHome")
    .orElse(System.getenv("GHIDRA_HOME"))
    .orElse("/opt/homebrew/var/homebrew/tmp/.cellar/ghidra/12.1")
    .get()

val ghidraSupportDir = file("$ghidraHome/libexec/support")

val javatoolsClasses: ConfigurableFileCollection = files(
    "$rootDir/../../xvm/javatools/build/classes/java/main"
)

// Copy the Ghidra script into resources so it can be found at runtime
val copyScript by tasks.registering(Copy::class) {
    from(file("src/jvmMain/resources/DumpPCode.java"))
    into(layout.buildDirectory.dir("scripts"))
}

// Task: dump P-code from xvm bytecode using Ghidra headless
val ghidraDump by tasks.registering(Exec::class) {
    group = "ghidra"
    description = "Dump P-code graph from xvm bytecode via Ghidra headless analysis"

    dependsOn(":lib_cursor:build", copyScript)

    workingDir = layout.buildDirectory.dir("ghidra-output").get().asFile
    outputs.dir(workingDir)

    commandLine(
        "$ghidraSupportDir/analyzeHeadless",
        workingDir,
        "xvm-pcode",
        "-scriptPath", copyScript.get().destinationDir.absolutePath,
        "-postScript", "DumpPCode.java",
        "-import", javatoolsClasses.asPath,
    )

    doFirst {
        workingDir.asFile.mkdirs()
    }
}

// Task: copy the dump output into lib_cursor for use as blackboard schema
val installDump by tasks.registering(Copy::class) {
    group = "ghidra"
    description = "Copy Ghidra P-code dump into lib_cursor as blackboard schema"
    dependsOn(ghidraDump)

    from(ghidraDump).into("$rootDir/lib_cursor")
    from(file("src/jvmMain/resources/DumpPCode.java")).into("$rootDir/lib_cursor")
}

tasks.named("assemble") {
    dependsOn(copyScript)
}

tasks.registering<PublishToMavenRepository> {
    // placeholder — publish is no-op for this utility module
}