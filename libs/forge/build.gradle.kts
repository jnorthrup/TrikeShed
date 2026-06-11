plugins {
    id("org.jetbrains.kotlin.jvm")
    id("java-library")
    id("maven-publish")
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    google()
    gradlePluginPortal()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://www.jitpack.io") }
}

dependencies {
    // Core TrikeShed kernel algebra (JVM-only libs)
    implementation(project(":libs:common"))
    implementation(project(":libs:concurrency"))
    
    // Couch for document management, snapshots, VCS
    implementation(project(":libs:couch"))
    implementation(project(":libs:miniduck"))
    implementation(project(":libs:kursive"))
    implementation(project(":libs:polyglot"))
    
    // Serialization / wireproto
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    
    // HTTP client for agent API calls
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    
    // JUnit Jupiter for TDD tests
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.0")
    
    // Kotlin test extensions
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.0")
    
    // Testcontainers for integration tests
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation(project(":libs:couch"))
}

// Exclude JUnit 4 to avoid framework conflict with JUnit 5
configurations {
    named("testCompileClasspath") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit")
    }
    named("testRuntimeClasspath") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit")
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("test") {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}