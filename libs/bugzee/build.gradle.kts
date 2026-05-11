apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    add("commonMainImplementation", project(":libs:miniduck"))
    add("commonTestImplementation", project(":libs:miniduck"))
}
