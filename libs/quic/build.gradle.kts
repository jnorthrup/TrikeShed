plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {}
    sourceSets {
        val commonMain = getByName("commonMain") {
            dependencies {
                api(project(":"))
            }
        }
        val jvmMain = getByName("jvmMain") {
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
