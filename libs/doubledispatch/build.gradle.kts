plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {}
    sourceSets {
        val commonMain by getting {}
        val jvmMain by getting {}
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}
