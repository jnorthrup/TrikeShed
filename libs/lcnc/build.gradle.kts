plugins {
    kotlin("multiplatform")
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
