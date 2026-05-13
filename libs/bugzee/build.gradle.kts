apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    add("commonMainApi", project(":libs:hazelnut"))
    add("commonMainImplementation", project(":libs:miniduck"))
    add("commonTestImplementation", project(":libs:miniduck"))
}
