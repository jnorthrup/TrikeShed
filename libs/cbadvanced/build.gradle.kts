plugins {
    kotlin("jvm")
    application
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

val kotlinVersion = "2.4.0-RC2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xsuppress-version-warnings",
        )
    }
}

application {
    mainClass.set("cbadvanced.main.CbAdvancedMainKt")
}

dependencies {
    implementation(project(":libs:dreamer-kmm"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("versions.kotlinx-coroutines-core")}")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("versions.kotlinx-coroutines-test")}")
}

tasks.test {
    useJUnit()
}

tasks.register<JavaExec>("authProof") {
    group = "cbadvanced"
    description = "Run the Coinbase Advanced Trade auth sample against the repo .env"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("cbadvanced.main.CbAdvancedMainKt")
    workingDir = project.projectDir
    jvmArgs("-ea")
}
