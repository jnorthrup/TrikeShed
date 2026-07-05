apply(from = "../../gradle/macros/trikeshed-lib.gradle")

val openApiProject = project(":libs:openapi")

// OpenAPI Choreography Demo run task
tasks.register<JavaExec>("runOpenApiChoreographyDemo") {
    group = "run"
    description = "Runs the OpenAPI Choreography demo on JVM."
    dependsOn(openApiProject.tasks.getByName("jvmJar"))
    mainClass.set("borg.trikeshed.openapi.demo.OpenApiChoreographyDemo")
    classpath(openApiProject.tasks.getByName("jvmJar").outputs.files, configurations.getByName("jvmRuntimeClasspath"))
}
