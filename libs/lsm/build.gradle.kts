apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    // LSM depends on core lib for Series/Join/Twin
    implementation(project(":libs:common"))
    
    // Test dependencies
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}