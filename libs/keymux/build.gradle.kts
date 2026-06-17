apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    // Serialization for JSON handling
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")

    // HTTP client for provider discovery
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")

    // Date/time for quota timeframes
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-rc02-0.6.x-compat")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")

    // TrikeShed core
    api(project(":"))
}
