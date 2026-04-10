rootProject.name = "trikeshed"

// Include literbike as a composite build (CCEK + literbike modules)
includeBuild("libs/literbike") {
    dependencySubstitution {
        substitute(module("org.bereft:literbike")).using(project(":"))
    }
}

// Include seaofnodes as a composite build
includeBuild("libs/seaofnodes") {
    dependencySubstitution {
        substitute(module("org.bereft:seaofnodes")).using(project(":"))
    }
}
