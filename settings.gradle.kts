import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

tasks.register<Test>("focusedTransportTest") {
    description = "Runs the focused JVM transport/routing slice."
    group = "verification"
    val jvmTestComp = kotlin.targets.getByName("jvm").compilations.getByName("test")
    val jvmTestTask = tasks.named<Test>("jvmTest")
    testClassesDirs = jvmTestTask.get().testClassesDirs
    classpath = files(
        jvmTestComp.runtimeDependencyFiles,
        jvmTestComp.output.allOutputs,
        jvmTestTask.get().outputs.files
    )
    include("**/ChannelizationSelectionTest.class")
    include("**/ChannelizationProjectionTest.class")
    include("**/ProtocolRouterTest.class")
    include("**/SelectorTransportBackendTest.class")
    include("**/LinuxNativeTransportBackendTest.class")
    include("**/CcekTransportCapabilityTest.class")
    shouldRunAfter(jvmTestTask)
}

val jmhTask = tasks.register<JavaExec>("jmh") {
    description = "Runs JMH benchmarks"
    group = "benchmark"
    dependsOn("compileKotlinJvm")
    dependsOn("jvmJar")

    val jvmComp = kotlin.targets.getByName("jvm").compilations.getByName("main")
    classpath = jvmComp.runtimeDependencyFiles ?: files()
    classpath += files(jvmComp.output.classesDirs)
    classpath += files(tasks.getByName("jvmJar").outputs.files)

    mainClass.set("org.openjdk.jmh.Main")
}

tasks.register("benchmark") {
    description = "Runs all benchmarks (tests + JMH)"
    group = "verification"
    dependsOn("test")
    dependsOn(jmhTask)
}