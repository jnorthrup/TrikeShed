plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {}
    sourceSets {
        val commonMain = getByName("commonMain") {}
        val jvmMain = getByName("jvmMain") {}
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}
