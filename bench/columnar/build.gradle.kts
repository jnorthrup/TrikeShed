plugins { kotlin("multiplatform") version "2.4.20" }
repositories { mavenCentral() }

// Actual production sources, selected to exclude the unrelated broken root UI/transport build.
val commonSources = listOf(
    "lib/Join.kt", "lib/PrimitiveJoins.kt", "lib/Series.kt", "lib/Series2.kt",
    "lib/ReifiedSplitSeries2.kt", "lib/Twin.kt", "lib/Twins.kt", "lib/AutoTwinContext.kt",
    "lib/CharSeries.kt", "lib/ByteSeries.kt", "lib/CZero.kt", "lib/TypeEvidence.kt", "lib/debug.kt",
    "context/BitMasked.kt", "isam/meta/IOMemento.kt", "isam/meta/PlatformCodec.kt",
    "cursor/Cursor.kt", "cursor/CursorOps.kt", "cursor/StructuralTypeMemento.kt",
    "cursor/GroupBy.kt", "cursor/Pivot.kt", "cursor/ReifiedSplitSeries2.kt", "cursor/CursorIndexing.kt",
)
kotlin {
    jvmToolchain(25)
    jvm()
    sourceSets {
        commonMain {
            kotlin.srcDir("../../src/commonMain/kotlin")
            commonSources.forEach { kotlin.include("borg/trikeshed/$it") }
            dependencies { implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0-0.6.x-compat") }
        }
        jvmMain {
            kotlin.srcDir("src/main/kotlin")
            kotlin.srcDir("../../src/jvmMain/kotlin")
            kotlin.include("borg/trikeshed/bench/columnar/**", "borg/trikeshed/lib/assert.kt")
        }
        jvmTest {
            kotlin.srcDir("../../src/commonTest/kotlin")
            kotlin.include("borg/trikeshed/cursor/CursorColumnarTest.kt", "borg/trikeshed/lib/IterableProjectionTest.kt")
            dependencies { implementation(kotlin("test-junit5")); runtimeOnly("org.junit.platform:junit-platform-launcher") }
        }
    }
}
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.register<JavaExec>("bench") {
    val main = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("borg.trikeshed.bench.columnar.CursorBenchmarkKt")
    args(providers.gradleProperty("rows").getOrElse("65536"),
        providers.gradleProperty("warmup").getOrElse("3"),
        providers.gradleProperty("iterations").getOrElse("7"),
        providers.gradleProperty("results").getOrElse("results/cursor.json"))
}
