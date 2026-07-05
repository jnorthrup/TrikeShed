plugins {
    id("java-library")
    id("maven-publish")
}

group = "borg.trikeshed"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    mavenLocal()
    google()
    gradlePluginPortal()
    maven { url = uri("https://oss.sonatype.org/content/repositories/snapshots/") }
    maven { url = uri("https://www.jitpack.io") }
}

dependencies {
    // ASM for bytecode manipulation
    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation("org.ow2.asm:asm-analysis:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
    
    // For agent manifest
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
}

// Configure jar manifest for Java agent
tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Premain-Class" to "borg.trikeshed.agent.JvmPointcutAgent",
            "Agent-Class" to "borg.trikeshed.agent.JvmPointcutAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true"
        )
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
    options.release.set(21)
}