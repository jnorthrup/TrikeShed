plugins {
    alias(libs.plugins.trikehed.multiplatform)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":lib"))
            }
        }
        commonTest {
            dependencies {
                implementation(project(":lib:test"))
            }
        }
    }
}