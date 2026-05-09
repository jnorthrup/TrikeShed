apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    "commonMainImplementation"(project(":libs:kursive"))
            "commonMainImplementation"(project(":libs:narsive"))
            "commonMainImplementation"(project(":libs:nars3"))
    "commonMainImplementation"(project(":libs:cpu-cache"))
}
