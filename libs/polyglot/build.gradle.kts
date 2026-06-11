apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    "jvmMainImplementation"("org.graalvm.polyglot:polyglot:24.1.1")
    "jvmMainImplementation"("org.graalvm.sdk:graal-sdk:24.1.1")
    "jvmTestRuntimeOnly"("org.graalvm.polyglot:python:24.1.1")

    // JUnit Jupiter for TDD tests (JUnit 5)
    "jvmTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    "jvmTestImplementation"("org.junit.jupiter:junit-jupiter-params:5.10.2")

    // Kotlin test extensions - use JUnit 5 variant
    "jvmTestImplementation"("org.jetbrains.kotlin:kotlin-test:2.4.0")
    "jvmTestImplementation"("org.jetbrains.kotlin:kotlin-test-junit5:2.4.0")
}

// Exclude kotlin-test-junit (JUnit 4) from macro to avoid framework conflict with JUnit 5
configurations {
    named("jvmTestCompileClasspath") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit")
    }
    named("jvmTestRuntimeClasspath") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit")
    }
}

tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    useJUnitPlatform()
}