plugins {
    kotlin("jvm")
    application
}

group = "org.bereft"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()
    gradlePluginPortal()
}

dependencies {
    implementation(project(":libs:couch"))
    implementation(project(":libs:miniduck"))
    implementation(project(":libs:kursive"))
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

application {
    // main class will be set in source as RunSqlIntegrationKt
    mainClass.set("borg.trikeshed.integration.RunSqlIntegrationKt")
}
