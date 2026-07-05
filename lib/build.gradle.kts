plugins {
    kotlin("multiplatform") version "2.4.0"
}

group = "borg.trikeshed"
version = "1.0"

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvmToolchain(25)
    jvm()

    sourceSets {
        val commonMain = getByName("commonMain")
    }
}
