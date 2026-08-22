Since we just need to pass the file path and content, we can do exactly what I had: for each touched path, emit a `PatchData(path.substringAfterLast('/'), path, arm.patch)`. But to avoid duplicate `arm.patch` in ChiSq computation, we could pass `arm.patch` for the FIRST file, and `""` (empty string) for the subsequent files. Or we can just use `arm.patch` for the first file, and for the rest just the path:
```kotlin
            val patches = touched.mapIndexed { index, path ->
                borg.trikeshed.userspace.containment.PatchData(
                    fileName = path.substringAfterLast('/'), 
                    filePath = path, 
                    content = if (index == 0) arm.patch else ""
                )
            }
```
Yes, this avoids multiplying the character counts for the `calculateChiSquared` function in `StigmergicProtocolDecoder`. 
The decoder uses:
1. `p.fileName.startsWith`
2. `p.filePath.split('/').size`
3. `p.content.split(...)` (Lexical)
4. `p.content` joining (Statistical)

So giving the content only to the first file's `PatchData` is perfect. It tests the naming/path logic for ALL touched files, and the content logic ONCE.

Let's refine the plan:
```markdown
1. Edit `FlywheelDriver.kt` inside `preflightPijulBatch`.
2. Change the method signature from `List<Arm>` to `MutableList<Arm>`.
3. Before `// 1. Collect all valid touched paths`, create `val decoder = borg.trikeshed.userspace.containment.StigmergicProtocolDecoder()` and `val suspiciousArms = mutableListOf<Arm>()`.
4. While filtering `arms` to find `validArms`:
   - Parse files `touched = parsePatchFiles(arm.patch).filterNot(::isUnsafeAutomaticPatchPath)`.
   - If `touched` is empty, filter out (return false).
   - Create `PatchData` list from `touched` where the first element gets `arm.patch` as content, others get `""`.
   - Call `decoder.decode(patches)` (no historicalTokens needed based on prompt, or if needed we don't have them easily accessible, default is emptySet which is fine for naming/directory checks and stats).
   - If `isSuspicious` is true, add to `suspiciousArms`, call `drainFail(arm.session, "quarantined by StigmergicProtocolDecoder: \${detection.protocolName}")`, and return false.
   - Else return true.
5. After `validArms` is constructed, call `arms.removeAll(suspiciousArms)`.
6. Run the GATE `./gradlew jvmMainClasses --console=plain` to verify the build.
7. Complete pre-commit step.
8. Submit code.
```
