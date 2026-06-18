plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {}
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":libs:lib"))
            }
        }
        val jvmMain by getting {
            dependencies {
                api(project(":libs:lib"))
            }
        }
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}
