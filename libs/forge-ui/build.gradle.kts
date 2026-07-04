import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// Forge UI module - handled by trikeshed-lib.gradle macro
apply(from = "../../gradle/macros/trikeshed-lib.gradle")

extensions.configure<KotlinMultiplatformExtension>("kotlin") {
    sourceSets.getByName("commonMain") {
        kotlin.srcDir("src/commonMain/kotlin")
    }
    sourceSets.getByName("jvmMain") {
        kotlin.srcDir("src/main/kotlin")
    }
    sourceSets.getByName("jvmTest") {
        kotlin.srcDir("src/test/kotlin")
    }
    sourceSets.getByName("jsMain") {
        kotlin.srcDir("src/jsMain/kotlin")
        resources.srcDir("src/jsMain/resources")
    }
}

// Avoid duplicate index.html collisions between the browser target and our custom shell.
tasks.matching { it.name == "jsProcessResources" }.configureEach {
    (this as? Copy)?.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// JVM launcher for the interactive desktop shell.
tasks.register<JavaExec>("runForgeUi") {
    group = "forge"
    description = "Run the Forge UI desktop shell (showcase + board)"
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.forge.ui.MainKt")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
}

// Headless walkthrough runner task — no screen, no Compose, pure Java2D + ffmpeg
tasks.register<JavaExec>("runHeadless") {
    group = "forge"
    description = "Run the headless walkthrough recorder (no GUI, outputs MP4)"
    dependsOn("jvmJar")
    mainClass.set("borg.trikeshed.forge.ui.RunHeadlessKt")
    classpath(tasks.named("jvmJar"), configurations.getByName("jvmRuntimeClasspath"))
    environment("PATH", System.getenv("PATH") ?: "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin")
    jvmArgs("-Djava.awt.headless=true")
}
