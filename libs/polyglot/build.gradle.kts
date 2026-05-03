apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    "commonMainImplementation"(project(":libs:kursive"))
    "commonMainImplementation"(project(":libs:cpu-cache"))
}
