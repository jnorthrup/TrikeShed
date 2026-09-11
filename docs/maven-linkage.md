# JVM publication and guest module linkage

The root project publishes the complete JVM library as `borg.trikeshed:TrikeShed-jvm:0.1.0-SNAPSHOT`. This is the current group, artifact and version from the generated POM; historical `org.bereft:TrikeShed-jvm:1.0` coordinates do not identify this build.

```sh
./gradlew publishJvmPublicationToMavenLocal -Dmaven.repo.local="$PWD/build/linkage-m2" --console=plain
```

A separate Gradle consumer can use:

```kotlin
repositories {
    maven { url = uri("/absolute/path/to/TrikeShed/build/linkage-m2") }
    mavenCentral()
    google()
}
dependencies {
    implementation("borg.trikeshed:TrikeShed-jvm:0.1.0-SNAPSHOT")
}
```

Use JDK 25. Maven publication supplies the actual JVM jar, source jar, Gradle module metadata and dependency POM. The external polyglot project consumes this artifact; its focused Cursor benchmark source slice is not presented as a full library package. The external bundle includes its own repository copy and artifact SHA-256 inventory.

The existing `utils/subvm` installer owns isolated guest dependencies:

```sh
./gradlew -p utils/subvm installCamelJdbc --console=plain
```

`camel-jdbc` inherits the `camel` module and adds Camel JDBC 4.8.5 and H2 2.3.232. Installing a child now installs its declared parent first. `MANIFEST.tsv` records resolved jar names, byte sizes and SHA-256 hashes; downloaded `lib` directories remain ignored. The conservative `CamelLinkage` JDBC row requires department reach because a registered DataSource can point to another machine even when its endpoint string contains no address.

## Verified boundary

Root JVM jar generation and publication completed. Two external consumers resolve the complete coordinate: numerical Graal execution through `SubgraalPointcutRunner`, and the distinct direct JDBC/Camel workload through `HypervisorVmHost`, `GuestIsolate` and `GuestModules`. Reflection in `CamelRuntime` alone is not evidence of Graal guest execution; the latter benchmark enters an actual GraalJS isolate and labels its Java interop explicitly.

The root initially failed before reaching tests. The prerequisite repair removes duplicate UringChannel/UringChannels declarations, restores the List-returning KanbanGraph expression, extracts the already-used curator record map, and repairs Iterable projection. A test double's stale `FileChannel.map` return type was updated to `MemoryMapping`. These are build/API failures, separately recorded from guest regression failures. The final selected root runs passed 55 tests across CursorColumnarTest, IterableProjectionTest, SubgraalSourceFilterTest, UringConformanceTest, CamelLinkageTest, JvmIsamOperationsTest, IsamMetaFileReaderTest and IpfsBridgeTest. This does not claim a complete multiplatform suite. Separate core benchmark evidence records the actual Linux JVM/JNI native run.
