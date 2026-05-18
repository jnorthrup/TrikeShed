apply(from = "../../gradle/macros/trikeshed-lib.gradle")

// Add project dependency on libs:miniduck for both main and test source sets so tests can reference RowVec types.
dependencies {
    // Kotlin Multiplatform creates configurations like 'commonMainImplementation' and 'commonTestImplementation'
    add("commonMainImplementation", project(":libs:miniduck"))
    add("commonTestImplementation", project(":libs:miniduck"))
}

// Note: removed brittle reflection-based afterEvaluate injection; explicit configuration adds are used so
// the project dependency is present during compilation.
