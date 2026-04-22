plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenCentral()
}

group = "org.bbcursive"
version = "0.1.0"

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
