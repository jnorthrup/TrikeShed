plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

// JVM test runner for dreamer backtests.  Add direct JVM deps on KMP modules that
// aren't re-exported transitively by dreamer-kmm's JVM artifact so the JVM
// compiler sees the concrete classes (MiniDuck, Couch) during compilation.
dependencies {
    implementation(project(":libs:dreamer-kmm"))
    implementation(project(":libs:miniduck"))
    implementation(project(":libs:couch"))

    // borg.trikeshed.lib (Series, size, j, Twin, Join) lives in root commonMain
    implementation(project(":"))

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-core").get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${rootProject.providers.gradleProperty("versions.kotlinx-coroutines-test").get()}")
}

tasks.test {
    useJUnit()
}

kotlin {
    jvmToolchain(21)
}
