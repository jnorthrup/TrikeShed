plugins { kotlin("jvm") version "2.4.20"; application }
repositories { mavenCentral() }
val trikeshedJar = file("../../../build/libs/TrikeShed-jvm-0.1.0-SNAPSHOT.jar")
kotlin {
    jvmToolchain(25)
    // The benchmark uses the existing internal channel-factory test seam; production API stays unchanged.
    compilerOptions { freeCompilerArgs.add("-Xfriend-paths=${trikeshedJar.absolutePath}") }
}
dependencies {
    implementation(files(trikeshedJar))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
application { mainClass.set("borg.trikeshed.bench.columnar.IsamIoBenchmarkKt") }
tasks.register<JavaExec>("bench") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("borg.trikeshed.bench.columnar.IsamIoBenchmarkKt")
    doFirst { check(trikeshedJar.exists()) { "Build the actual root jvmJar first" } }
    args(providers.gradleProperty("mode").getOrElse("emulated"),
        providers.gradleProperty("rows").getOrElse("1024"),
        providers.gradleProperty("warmup").getOrElse("1"),
        providers.gradleProperty("iterations").getOrElse("3"),
        providers.gradleProperty("results").getOrElse("../results/isam-io.json"),
        providers.gradleProperty("trace").getOrElse("off"),
        providers.gradleProperty("traceOps").getOrElse(""),
        providers.gradleProperty("tracePhases").getOrElse(""),
        providers.gradleProperty("traceEvery").getOrElse("1"))
    providers.gradleProperty("uringLibrary").orNull?.let { systemProperty("trikeshed.uring.library", it) }
}
tasks.register("runtimeClasspath") {
    dependsOn(tasks.classes)
    doLast { layout.buildDirectory.file("runtime-classpath.txt").get().asFile.writeText(sourceSets.main.get().runtimeClasspath.asPath) }
}
