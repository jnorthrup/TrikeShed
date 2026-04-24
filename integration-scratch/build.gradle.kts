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
}

application {
    // main class will be set in source as RunSqlIntegrationKt
    mainClass.set("borg.trikeshed.integration.RunSqlIntegrationKt")
}
