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
}

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin.sourceSets["commonMain"].dependencies {
    api(project(":"))
}
