// Top-level build file for KMPngSCTP - Next Generation SCTP Protocol
plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
}

group = "dev.jnorthrup"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
