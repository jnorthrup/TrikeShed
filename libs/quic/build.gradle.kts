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
        val jvmMain by getting {
            dependencies {
                api(project(":"))
            }
        }
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}
