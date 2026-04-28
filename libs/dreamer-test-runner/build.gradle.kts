plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":libs:dreamer-kmm"))
    implementation(project(":libs:couch"))
    implementation(project(":libs:miniduck"))
    // borg.trikeshed.lib (Series, size, j, Twin, Join) lives in root commonMain
    implementation(project(":"))
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

tasks.test {
    useJUnit()
}

kotlin {
    jvmToolchain(21)
}
