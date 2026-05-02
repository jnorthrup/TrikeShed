apply(from = "../../gradle/macros/trikeshed-lib.gradle")

val openapiRuntime: Configuration by configurations.creating

// htx-client must be built BEFORE generation runs, so use project reference
val htxClientProject = project(":libs:htx-client")

dependencies {
    openapiRuntime(project(":libs:openapi"))
    openapiRuntime(htxClientProject) // Include for classpath during generation
}

val kotlinExt = extensions.getByName("kotlin") as org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
kotlinExt.sourceSets.getByName("commonMain").dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.extra["versions.kotlinx-coroutines-core"]}")
    api(project(":libs:openapi"))
    api(htxClientProject) // API dep for compilation
    api(project(":")) // Root for Confix parsing
}

// Generated sources go to build dir
val generatedSrcDir = layout.buildDirectory.dir("generated-sources/jules-client")

// Generate to build directory
val openApiGenerateJulesClient by tasks.registering(JavaExec::class) {
    group = "openapi"
    description = "Generates htx-client API sources for Jules from the OpenAPI contract."
    dependsOn(":libs:openapi:jvmJar")
    dependsOn(htxClientProject.tasks.named("jvmJar")) // Build htx-client first

    val openApiSpec = layout.projectDirectory.file("openapi/jules.openapi.yaml")
    val outputDir = generatedSrcDir.get()

    inputs.file(openApiSpec)
    inputs.files(project(":libs:openapi").tasks.named("jvmJar"))
    inputs.files(htxClientProject.tasks.named("jvmJar"))
    inputs.files(openapiRuntime)
    outputs.dir(outputDir)

    classpath = project(":libs:openapi").tasks.named("jvmJar").get().outputs.files +
            htxClientProject.tasks.named("jvmJar").get().outputs.files +
            openapiRuntime
    mainClass.set("borg.trikeshed.openapi.GenerateSourcesKt")
    args(
        "--spec", openApiSpec.asFile.absolutePath,
        "--target", "jules",
        "--output", outputDir.asFile.absolutePath,
        "--sides", "client"
    )

    doFirst {
        outputDir.asFile.deleteRecursively()
        outputDir.asFile.mkdirs()
    }
}

// Add generated sources after generation task runs
kotlinExt.sourceSets.getByName("commonMain").kotlin.srcDir(generatedSrcDir.map { it.asFile })

val verifyJulesClientGeneratedSources by tasks.registering {
    group = "openapi"
    dependsOn(openApiGenerateJulesClient)
}

tasks.named("compileKotlinJvm").configure {
    dependsOn(openApiGenerateJulesClient)
}