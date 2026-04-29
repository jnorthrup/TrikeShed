plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    google()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://www.jitpack.io")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api("org.bereft:TrikeShed:1.0")
    api(project(":"))
    testImplementation(kotlin("test"))
}
