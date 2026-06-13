apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    // GraalVM Polyglot API - SDK bundle includes all languages
    "jvmMainImplementation"("org.graalvm.polyglot:polyglot:24.1.1")
    "jvmMainImplementation"("org.graalvm.sdk:graal-sdk:24.1.1")

    // Python on GraalVM for Hermes instrumentation
    "jvmTestRuntimeOnly"("org.graalvm.polyglot:python:24.1.1")
    "jvmTestRuntimeOnly"("org.graalvm.polyglot:js:24.1.1")

    // SQLite JDBC for embedded analytical DBMS
    "jvmMainImplementation"("org.xerial:sqlite-jdbc:3.49.1.0")

    // Arrow for Feather/Arrow isomorphism (analytical layer)
    "jvmMainImplementation"("org.apache.arrow:arrow-memory-core:19.0.1")
    "jvmMainImplementation"("org.apache.arrow:arrow-vector:19.0.1")
    "jvmMainImplementation"("org.apache.arrow:arrow-format:19.0.1")
    "jvmMainImplementation"("org.apache.arrow:arrow-memory-netty:19.0.1")

    // Netty for off-heap buffer management
    "jvmMainImplementation"("io.netty:netty-buffer:4.1.118.Final")
    "jvmMainImplementation"("io.netty:netty-transport:4.1.118.Final")

    // Kotlinx serialization for wire protocol
    "jvmMainImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines for supervisor context
    "jvmMainImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0-rc02")

    // JUnit 5 for TDD
    "jvmTestImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    "jvmTestImplementation"("org.junit.jupiter:junit-jupiter-params:5.10.2")
    "jvmTestImplementation"("org.jetbrains.kotlin:kotlin-test-junit5:2.4.0")

    // Internal TrikeShed dependencies
    "jvmMainImplementation"(project(":libs:polyglot"))
    "jvmMainImplementation"(project(":libs:ccek-dsl"))
    "jvmMainImplementation"(project(":libs:uring"))
}

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
    // GraalVM requires these flags for polyglot testing
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "-Dpolyglot.engine.WarnInterpreterOnly=false"
    )
}