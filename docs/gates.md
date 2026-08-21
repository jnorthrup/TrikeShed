# TrikeShed Development Gates

The following validation gates are run to ensure PRs are safe to merge:

*   `jvmMainClasses`: Validates that the core JVM target compiles cleanly.
*   `compileKotlinJs`: Validates that the JS target (specifically the web app layer) compiles cleanly. Ensures `drainExactArtifacts` and `commonMain` pure files remain agnostic to JVM-specific libraries.
*   `commonMainPurity`: A custom shell verification task that guarantees the `commonMain` source set remains completely devoid of platform-specific bindings. It fails the build if `java.*`, `System.*`, `Dispatchers.IO`, `Charsets`, `String.format`, `java.nio`, `Selector`, `SocketChannel`, or incorrect uses of `@JvmInline` are found in `src/commonMain/**/*.kt` without an explicit `// purity:allow <reason>` bypass comment on the line.

### Running Gates Manually

```bash
./gradlew jvmMainClasses --console=plain
./gradlew compileKotlinJs --console=plain
./gradlew commonMainPurity --console=plain
```
