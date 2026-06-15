plugins {
    kotlin("multiplatform") version "2.4.0"
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

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

kotlin.sourceSets["commonMain"].dependencies {
    api(kotlin("stdlib-common"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

// JVM target depends on GraalVM Polyglot for ECMA launcher
kotlin.sourceSets["jvmMain"].dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.graalvm.polyglot:polyglot:25.0.2")
    implementation("org.graalvm.polyglot:js-community:25.0.2")
    implementation("org.graalvm.js:js:25.0.2")
}

kotlin.sourceSets["jsMain"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin.sourceSets["wasmJsMain"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

kotlin.sourceSets["linuxX64Main"].dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}