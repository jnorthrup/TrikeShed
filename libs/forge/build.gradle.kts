buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.4.0")
    }
}

apply(from = "../../gradle/macros/trikeshed-lib.gradle")
