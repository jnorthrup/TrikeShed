// publish_macro.gradle.kts - Add mavenLocal publishing tasks to root project

tasks.register("publishMavenLocalMacro") {
    group = "publishing"
    description = "Publish all KMP artifacts to mavenLocal for downstream consumption (e.g. lib_cursor)"
    doFirst {
        println("=== Publishing TrikeShed to mavenLocal ===")
    }

    // Find all publish*Local tasks across subprojects by name pattern
    subprojects.forEach { proj ->
        proj.tasks.forEach { task ->
            if (task.name.startsWith("publish") && task.name.endsWith("ToMavenLocal")) {
                println("  Adding dependency: ${proj.name}:${task.name}")
                dependsOn(proj.tasks.named(task.name))
            }
        }
    }
}

tasks.register("cleanPublishMavenLocal") {
    group = "publishing"
    description = "Clean build and publish all artifacts to mavenLocal"
    dependsOn("clean", "publishMavenLocalMacro")
    doFirst {
        println("=== Clean build + publish to mavenLocal ===")
    }
}