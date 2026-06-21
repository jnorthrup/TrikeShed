plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.7.3"
}

apply(from = "../../gradle/macros/trikeshed-lib.gradle")

// Copy index.html and auth.json to build/dist on build
tasks.register<Copy>("copyUiDist") {
    from("src/main/kotlin/borg/trikeshed/forge/ui/index.html")
    into(layout.buildDirectory.dir("dist"))
}

tasks.register<Copy>("copyAuthJson") {
    from("src/main/kotlin/borg/trikeshed/forge/ui/auth.json")
    into(layout.buildDirectory.dir("dist"))
}

tasks.matching { it.name == "build" }.configureEach {
    dependsOn("copyUiDist")
    dependsOn("copyAuthJson")
}