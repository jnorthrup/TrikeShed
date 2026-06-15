plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    google()
    maven("https://www.jitpack.io")
}

kotlin {
    jvmToolchain(25)
    jvm {}

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }
    }
}
