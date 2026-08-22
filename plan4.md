Let's see if we can do:
```kotlin
<<<<<<< SEARCH
    private suspend fun preflightPijulBatch(
        arms: List<Arm>,
        baseSha: String,
    ): Pair<List<Arm>, String>? {
        if (arms.size <= 1) return null
        val channel = borg.trikeshed.pijul.PijulChannel()
        val fileOps = JvmFileOperations()

        // 1. Collect all valid touched paths
        val validArms = arms.filter { arm ->
            val touched = parsePatchFiles(arm.patch).filterNot(::isUnsafeAutomaticPatchPath)
            touched.isNotEmpty()
        }
        if (validArms.size <= 1) return null

        val allTouched = validArms.flatMap { parsePatchFiles(it.patch).filterNot(::isUnsafeAutomaticPatchPath) }.distinct()
=======
    private suspend fun preflightPijulBatch(
        arms: MutableList<Arm>,
        baseSha: String,
    ): Pair<List<Arm>, String>? {
        if (arms.size <= 1) return null
        val channel = borg.trikeshed.pijul.PijulChannel()
        val fileOps = JvmFileOperations()

        val decoder = borg.trikeshed.userspace.containment.StigmergicProtocolDecoder()
        val suspiciousArms = mutableListOf<Arm>()

        // 1. Collect all valid touched paths
        val validArms = arms.filter { arm ->
            val touched = parsePatchFiles(arm.patch).filterNot(::isUnsafeAutomaticPatchPath)
            if (touched.isEmpty()) return@filter false
            
            val patches = touched.map { path ->
                borg.trikeshed.userspace.containment.PatchData(path.substringAfterLast('/'), path, arm.patch)
            }
            val detection = decoder.decode(patches)
            if (detection.isSuspicious) {
                println("[FLYWHEEL] QUARANTINE ${arm.session.id.takeLast(6)}: ${detection.protocolName} - ${detection.evidence}")
                drainFail(arm.session, "quarantined by StigmergicProtocolDecoder: ${detection.protocolName}")
                suspiciousArms.add(arm)
                false
            } else {
                true
            }
        }
        
        arms.removeAll(suspiciousArms)

        if (validArms.size <= 1) return null

        val allTouched = validArms.flatMap { parsePatchFiles(it.patch).filterNot(::isUnsafeAutomaticPatchPath) }.distinct()
>>>>>>> REPLACE
```
One problem: The `decode` method signature:
```kotlin
fun decode(patches: List<PatchData>, historicalTokens: Set<String> = emptySet()): ProtocolDetection
```
So we can just pass `patches`.

Wait, the instructions say: `decode each batch arm's patches for swarm naming/lexical protocols before merge; isSuspicious arms quarantine instead of merge`.
Does the instruction imply I should extract the content of the file from the patch?
`arm.patch` is the unified diff. The diff contains the content of the files (the added lines).
Is `arm.patch` enough for `content` in `PatchData`?
The diff has lines like `+ some new code`.
Let's see what `StigmergicProtocolDecoder.decode` does with `content`:
```kotlin
        val currentTokens = patches.flatMap { it.content.split(Regex("\\W+")).filter { it.length > 4 } }.toSet()
        // and
        val allContent = patches.joinToString("") { it.content }
        val chiSq = calculateChiSquared(allContent)
```
If we pass `arm.patch` as the content for EACH touched file, then `allContent` will have the patch duplicated for each touched file.
```kotlin
            val patches = touched.map { path ->
                borg.trikeshed.userspace.containment.PatchData(path.substringAfterLast('/'), path, arm.patch)
            }
```
Is that okay? It will duplicate the patch content string, which might affect `calculateChiSquared`.
But wait! `parsePatchFiles` just gives paths. To get the actual content of each file from the patch, we would need to parse the diff and extract the added lines per file. `PijulDiffParser.parse(arm.patch)` might give us `FileChange`s.
Let's see if we can use `arm.patch` once for the content. We can pass a single `PatchData` per arm, or we can just pass the path and the patch. But if it modifies multiple files, we should probably just pass the whole patch as content for the first file, or empty string for the rest, to avoid duplication.
Let's check how `PatchData` is used in tests.
