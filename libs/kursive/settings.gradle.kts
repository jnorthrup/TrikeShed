rootProject.name = "kursive"

includeBuild("../../") {
    dependencySubstitution {
        substitute(module("org.bereft:TrikeShed")).using(project(":"))
    }
}
