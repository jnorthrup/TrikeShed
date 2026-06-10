apply(from = "../../gradle/macros/trikeshed-lib.gradle")

dependencies {
    implementation(project(":libs:miniduck"))
    implementation(project(":libs:classfile:lib_cursor"))
    
    // JVM-only dependencies for console app
    implementation("software.amazon.awssdk:s3:2.25.0")
    implementation("com.google.cloud:google-cloud-storage:2.35.0")
    implementation("com.aliyun.oss:aliyun-sdk-oss:3.17.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.11.0")
}

kotlin {
    jvm {
        // Console app entry point
        mainClass.set("borg.trikeshed.ipfs.console.BigBuckBunnyVerifier")
    }
}

application {
    mainClass.set("borg.trikeshed.ipfs.console.BigBuckBunnyVerifier")
}