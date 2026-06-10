plugins {
    kotlin("multiplatform") version "2.4.0"
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    jvm()
    js { nodejs() }
    wasmJs { nodejs() }
    linuxX64()
}

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin.sourceSets["commonMain"].dependencies {
    api(kotlin("stdlib-common"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
}

// JVM target depends on lib_cursor (JVM-only) and TrikeShed-jvm
kotlin.sourceSets["jvmMain"].dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    implementation("org.bereft:TrikeShed-jvm:1.0") // from mavenLocal
    implementation(project(":libs:classfile:lib_cursor"))
}

kotlin.sourceSets["jsMain"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-js:1.11.0")
}

kotlin.sourceSets["wasmJsMain"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-wasm:1.11.0")
}

kotlin.sourceSets["linuxX64Main"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-native:1.11.0")
}