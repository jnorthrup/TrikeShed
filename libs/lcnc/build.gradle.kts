plugins {
    kotlin("multiplatform")
}

kotlin {
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
