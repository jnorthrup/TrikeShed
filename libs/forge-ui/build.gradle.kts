import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    `maven-publish`
}

group = "borg.trikeshed"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)

    jvm {}

    js {
        nodejs()
        browser {
            // JS test configuration disabled for now
        }
    }

    sourceSets {
        val commonMain by named("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                api(libs.compose.runtime)
                api(libs.compose.foundation)
                api(libs.compose.material3)
                api(libs.compose.components.resources)
            }
        }

        val commonTest by named("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.compose.ui.test.junit4)
            }
        }

        val jvmMain by named("jvmMain") {
            dependencies {
                api(project(":libs:forge"))
                api(project(":"))  // Root TrikeShed for Confix/Cursor/Series
                api(libs.kotlinx.serialization.json)
                api(compose.desktop.currentOs)
            }
        }

        val jvmTest by named("jvmTest") {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.junit.jupiter)
                implementation(libs.compose.ui.test.junit4)
            }
        }

        val jsMain by named("jsMain") {
            dependencies {
                api(libs.compose.web.core)
                api(libs.compose.web.svg)
            }
        }

        val jsTest by named("jsTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.compose.ui.test.junit4)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "borg.trikeshed.forge.ui.MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb,
                TargetFormat.Exe
            )
            packageName = "forge-ui"
            packageVersion = "1.0.0"
        }
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}