plugins {
    kotlin("multiplatform") version "2.4.0"
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    jvmToolchain(25)
    jvm()
    js { nodejs() }
    wasmJs { nodejs() }
    linuxX64()
}

repositories {
    mavenCentral()
    mavenLocal()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://www.jitpack.io") }
}

val coroutinesVersion = "1.11.0-rc02"

kotlin.sourceSets["commonMain"].dependencies {
    api(kotlin("stdlib-common"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}

// JVM target depends on GraalVM Polyglot for ECMA launcher
kotlin.sourceSets["jvmMain"].dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.graalvm.polyglot:polyglot:25.0.2")
    implementation("org.graalvm.polyglot:js-community:25.0.2")
    implementation("org.graalvm.js:js:25.0.2")
}

kotlin.sourceSets["jsMain"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}

kotlin.sourceSets["wasmJsMain"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}

kotlin.sourceSets["linuxX64Main"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}

kotlin.sourceSets["jvmTest"].dependencies {
    implementation(kotlin("test-junit"))
    implementation("org.junit.jupiter:junit-jupiter:5.11.0")
    implementation("org.junit.jupiter:junit-jupiter-params:5.11.0")
    implementation("org.junit.platform:junit-platform-launcher:1.11.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(listOf("-Xexpect-actual-classes"))
    }
}

// Configure JUnit 5 platform for JVM tests
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}