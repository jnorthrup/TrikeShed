plugins {
    kotlin("multiplatform") version "2.4.0"
    `maven-publish`
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                api("org.bereft:TrikeShed:1.0")
            }
        }
        jvmMain {
            dependencies {
                implementation("org.graalvm.polyglot:polyglot:24.1.1")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.bereft:TrikeShed:1.0")
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
                runtimeOnly("org.graalvm.polyglot:js:24.1.1")
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnit()
}