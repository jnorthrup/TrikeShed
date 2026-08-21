/*
 * bench/lemma — standalone Gradle build.
 *
 * Keeps Stanford CoreNLP (~500 MB with English models, GPLv3) out of the TrikeShed root build. Depends on
 * the root's JVM jar by file; build it first:
 *     (cd ../.. && ./gradlew jvmJar --console=plain)
 * then here:
 *     ./gradlew extractDictionary --console=plain      # CoreNLP → ../../src/commonMain/resources/nlp/lemma/en/
 *     ./gradlew bench --console=plain                  # CoreNLP live vs FunnelLemmatizer, fractal scales
 */
plugins {
    kotlin("jvm") version "2.4.20-Beta2"
    application
}

repositories { mavenCentral() }

val coreNlpVersion = "4.5.10"
val trikeshedJar = file("../../build/libs/TrikeShed-jvm-0.1.0-SNAPSHOT.jar")

kotlin { jvmToolchain(25) }

dependencies {
    implementation(files(trikeshedJar))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("edu.stanford.nlp:stanford-corenlp:$coreNlpVersion")
    implementation("edu.stanford.nlp:stanford-corenlp:$coreNlpVersion:models")
}

val resourcesOut = file("../../src/commonMain/resources/nlp/lemma/en")

tasks.register<JavaExec>("extractDictionary") {
    group = "lemma"
    description = "Run CoreNLP over the corpus and write observations.tsv + MANIFEST.txt into commonMain resources"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("borg.trikeshed.bench.lemma.ExtractLemmaDictionaryKt")
    doFirst { check(trikeshedJar.exists()) { "missing $trikeshedJar — run (cd ../.. && ./gradlew jvmJar) first" } }
    args = listOf(resourcesOut.absolutePath) + (project.findProperty("corpus")?.toString()?.split(',') ?: emptyList())
    jvmArgs = listOf("-Xmx4g")
}

tasks.register<JavaExec>("bench") {
    group = "lemma"
    description = "Benchmark CoreNLP live lemmatization vs FunnelLemmatizer frozen from observations.tsv"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("borg.trikeshed.bench.lemma.LemmaBenchmarkKt")
    doFirst { check(trikeshedJar.exists()) { "missing $trikeshedJar — run (cd ../.. && ./gradlew jvmJar) first" } }
    args = listOf(resourcesOut.absolutePath, file("RESULTS.md").absolutePath) +
        (project.findProperty("corpus")?.toString()?.split(',') ?: emptyList())
    jvmArgs = listOf("-Xmx4g")
}
