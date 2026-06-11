plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    jvm()
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                // Core TrikeShed kernel algebra
                api(project(":libs:common"))
                api(project(":libs:concurrency"))
                
                // Couch for document management, snapshots, VCS
                api(project(":libs:couch"))
                api(project(":libs:miniduck"))
                api(project(":libs:kursive"))
                api(project(":libs:polyglot"))
                
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
            }
        }
        val commonTest by getting {
            dependencies {
                // JUnit Jupiter for TDD tests
                implementation("org.junit.jupiter:junit-jupiter:5.11.0")
                implementation("org.junit.jupiter:junit-jupiter-params:5.11.0")
                
                // Kotlin test extensions
                implementation("org.jetbrains.kotlin:kotlin-test:2.4.0")
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.0")
                
                // Testcontainers for integration tests
                implementation("org.testcontainers:testcontainers:1.20.4")
                implementation("org.testcontainers:junit-jupiter:1.20.4")
                implementation("org.testcontainers:postgresql:1.20.4")
            }
        }
        val jvmTest by getting {
            dependsOn(commonTest)
        }
    }
}

// Exclude JUnit 4 to avoid framework conflict with JUnit 5
configurations {
    named("jvmTestCompileClasspath") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit")
    }
    named("jvmTestRuntimeClasspath") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit")
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

repositories {
    mavenCentral()
    mavenLocal()
    google()
    gradlePluginPortal()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://www.jitpack.io") }
}