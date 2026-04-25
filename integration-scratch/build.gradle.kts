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
    implementation(kotlin("stdlib"))
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

application {
    // main class will be set in source as RunSqlIntegrationKt
    mainClass.set("borg.trikeshed.integration.RunSqlIntegrationKt")
}
