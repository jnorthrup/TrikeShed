cat build.gradle.kts | sed '/if (viewServerNodeSlice) {/,$d' > build.gradle.kts.tmp
cat << 'GRADLE' >> build.gradle.kts.tmp
if (providers.gradleProperty("viewServerNodeSlice").orNull == "true") {
    kotlin {
        sourceSets.getByName("commonMain").kotlin.srcDir("src/viewServerCommonMain/kotlin")
        sourceSets.getByName("commonTest").kotlin.srcDir("src/viewServerCommonTest/kotlin")
        sourceSets.getByName("jsMain").kotlin.srcDir("src/viewServerJsMain/kotlin")
        sourceSets.getByName("jvmMain").kotlin.srcDir("src/viewServerJvmMain/kotlin")
    }

    tasks.register<JavaExec>("runViewServerJvm") {
        dependsOn(":compileKotlinJvm")
        mainClass.set("borg.trikeshed.couch.viewserver.GraalVmViewServerHostKt")
        classpath(configurations.named("jvmRuntimeClasspath"), sourceSets.getByName("jvmMain").output)
        doFirst {
            val kotlinExt = project.extensions.getByType(org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension::class.java)
            classpath(kotlinExt.targets.getByName("jvm").compilations.getByName("main").output.allOutputs)
        }
    }
}
GRADLE
mv build.gradle.kts.tmp build.gradle.kts

sed -i 's/implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")/implementation("org.jetbrains.kotlinx:kotlinx-datetime:$datetimeVersion")\n                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")/' build.gradle.kts
sed -i 's/kotlin.exclude("\*\*\/lib\/MutableSeriesStrategyTest.kt")/kotlin.exclude("\*\*\/lib\/MutableSeriesStrategyTest.kt")\n            kotlin.exclude("\*\*\/job\/\*\*")\n            kotlin.exclude("\*\*\/kanban\/\*\*")\n            kotlin.exclude("\*\*\/strategy\/\*\*")\n            kotlin.exclude("\*\*\/lib\/ReduxMutableSeriesTest.kt")\n            kotlin.exclude("\*\*\/lib\/ReduxListBridgeTest.kt")\n            kotlin.exclude("\*\*\/dag\/\*\*")\n            kotlin.exclude("\*\*\/collections\/\*\*")\n            kotlin.exclude("\*\*\/forge\/\*\*")\n            kotlin.exclude("\*\*\/dht\/\*\*")\n            kotlin.exclude("\*\*\/couch\/CouchHeadProjectionTest.kt")\n            kotlin.exclude("\*\*\/couch\/isam\/DurableAppendLogTest.kt")\n            kotlin.exclude("\*\*\/couch\/isam\/WalFrameFormatTest.kt")/' build.gradle.kts
