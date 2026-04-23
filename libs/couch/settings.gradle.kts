rootProject.name = "couch"

includeBuild("../../") {
    dependencySubstitution {
        substitute(module("org.bereft:TrikeShed")).using(project(":"))
    }
}
