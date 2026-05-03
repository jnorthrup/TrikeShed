plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":libs:common"))
                implementation(project(":libs:quic"))
                implementation(project(":libs:ngsctp"))
                implementation(project(":libs:htx-client"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0-rc02")
            }
        }
    }
}
