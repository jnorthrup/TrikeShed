apply(from = "../../gradle/macros/trikeshed-lib.gradle")

val openapiRuntime: Configuration by configurations.creating

dependencies {
    openapiRuntime(project(":libs:openapi"))
    // kmpJvm uses api/implementation via sourceSets
}

val kotlinExt = extensions.getByName("kotlin") as org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
kotlinExt.sourceSets.getByName("commonMain").dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.extra["versions.kotlinx-coroutines-core"]}")
    api(project(":libs:openapi"))
    api(project(":libs:htx-client"))
    api(project(":")) // Root for Confix parsing
}
kotlinExt.sourceSets.getByName("commonMain").kotlin.srcDir("src/generated/kotlin")

val openApiGenerateJulesClient by tasks.registering(JavaExec::class) {
    group = "openapi"
    description = "Generates htx-client API sources for Jules from the OpenAPI contract."
    dependsOn(":libs:openapi:jvmJar")

    val openApiSpec = layout.projectDirectory.file("openapi/jules.openapi.yaml")
    val generatorJarPath = project(":libs:openapi").layout.buildDirectory.file("libs/openapi-jvm-1.0.jar") // Reverting to explicit since project version changes
    val outputDir = layout.projectDirectory.dir("src/generated/kotlin")

    inputs.file(openApiSpec)
    inputs.files(project(":libs:openapi").tasks.named("jvmJar"))
    inputs.files(openapiRuntime)
    outputs.dir(outputDir)

    classpath = project(":libs:openapi").tasks.named("jvmJar").get().outputs.files + openapiRuntime
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

val verifyJulesClientGeneratedSources by tasks.registering {
    group = "openapi"
    dependsOn(openApiGenerateJulesClient)
}

tasks.named("compileKotlinJvm").configure {
    dependsOn(openApiGenerateJulesClient)
}
