rootProject.name = "trikeshed-seaofnodes"

// Include literbike as a composite build for CCEK core
includeBuild("../literbike") {
    dependencySubstitution {
        substitute(module("org.bereft:literbike")).using(project(":"))
    }
}
