Wait, if `arms` is passed into `preflightPijulBatch(arms, baseSha)`. If we change `arms` to a `MutableList` we can just `remove` from it?
`arms` is of type `List<Arm>`. We cannot `remove` from it. But wait, `arms` in `drainExactArtifacts` is actually:
```kotlin
        val arms = kotlinx.coroutines.coroutineScope {
            // ...
        }.awaitAll().filterNotNull().toMutableList()
```
`arms` IS a `MutableList<Arm>`!
```kotlin
        val collusionDetector = borg.trikeshed.userspace.containment.CrossInstanceCollusionDetector()
        val detectorInput = arms.map { Triple(it.session, it.branch, it.patch) }
        val collusionReport = collusionDetector.detect(detectorInput)
        if (collusionReport.flaggedSessions.isNotEmpty()) {
            println("[FLYWHEEL] COLLUSION DETECTED: innocence=${collusionReport.innocenceScore}, evidence=${collusionReport.evidence}")
            arms.removeAll { it.session.id in collusionReport.flaggedSessions }
        }
```
Yes! `arms` is a `MutableList`. However, in `preflightPijulBatch`, it is typed as `arms: List<Arm>`.
If we change the parameter to `arms: MutableList<Arm>`, we can remove the suspicious arms from it directly inside `preflightPijulBatch`!
Wait, but does the prompt allow changing the method signature? It says "OWN only preflightPijulBatch (~line 818) in FlywheelDriver.kt". So yes, we can modify the signature, OR we can cast it to `MutableList<Arm>` since we know the runtime type is a `MutableList`. But casting is unsafe and bad practice. Changing signature to `MutableList<Arm>` is cleaner, but if we don't own the caller (it says OWN ONLY preflightPijulBatch), we shouldn't change the signature if it can be avoided.
Wait, another way to "quarantine instead of merge" in `preflightPijulBatch` without touching `arms` is to add them to `landed` inside the returned `batchArms`? No, if we add them to the returned list, `drainExactArtifacts` will think they landed and will close them! We don't want to close them as landed. We want them to fail.

Let's look at `preflightPijulBatch` again. If we just leave them out of `validArms` and call `drainFail(arm.session, ...)`, what happens in the caller?
The caller sees they are not in `landed`, so they go into `remainingArms`.
Then `preflightExactPatch` is called.
Wait! In `preflightExactPatch`, the arm will just be merged sequentially. We don't want that.
Is there a way to prevent `preflightExactPatch` from merging it without removing it from `arms`?
If we append something to the reason string, or maybe just change the `Arm` object? `Arm` is a data class.
What if `arms` is passed as `MutableList<Arm>` in `preflightPijulBatch`? Yes, we can just change the signature to `arms: MutableList<Arm>`. Since the caller passes a `MutableList`, changing the signature of a `private` function `preflightPijulBatch` to accept `MutableList<Arm>` is perfectly fine. The prompt says "OWN only preflightPijulBatch", so we own that function. Modifying its signature is modifying the function.

Let's check `preflightPijulBatch` signature.
```kotlin
    private suspend fun preflightPijulBatch(
        arms: MutableList<Arm>,
        baseSha: String,
    ): Pair<List<Arm>, String>? {
```
If we do this, we can just `arms.remove(arm)` for suspicious arms.
Wait! If we iterate over `arms` while removing, we get `ConcurrentModificationException`. We should collect suspicious arms, then `arms.removeAll(suspiciousArms)`.
Wait, the instruction says: "decode each batch arm's patches for swarm naming/lexical protocols before merge; isSuspicious arms quarantine instead of merge (debt: decoder exists in commonMain/userspace/containment, never invoked; legion Doc02 §1 path names as coordination vectors e.g. swarm_hold_probe_ack)."

Let's write down the exact logic:
```kotlin
        val decoder = borg.trikeshed.userspace.containment.StigmergicProtocolDecoder()
        val suspiciousArms = mutableListOf<Arm>()
        
        val safeValidArms = validArms.filter { arm ->
            val patches = parsePatchFiles(arm.patch).map { path ->
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
        
        // If we change arms parameter to MutableList<Arm>:
        arms.removeAll(suspiciousArms)

        // Then continue with safeValidArms instead of validArms
```
Wait, if `arms` is `MutableList`, can we change it? Yes. Let's verify `arms` is indeed `MutableList` everywhere it's passed.
```kotlin
        val pijulBatchResult = if (arms.size > 1) preflightPijulBatch(arms, baseSha) else null
```
And `arms` is defined as:
```kotlin
        val arms = kotlinx.coroutines.coroutineScope { ... }.awaitAll().filterNotNull().toMutableList()
```
And we have:
```kotlin
        arms.removeAll { it.session.id in collusionReport.flaggedSessions }
```
So it is definitely a `MutableList`. If we change `preflightPijulBatch` signature to accept `MutableList<Arm>`, it's perfectly safe.

Alternatively, if we don't want to change the signature (because it might be considered outside the function body strictly speaking), we could cast: `(arms as MutableList<Arm>).removeAll(suspiciousArms)`. It's Kotlin, `arms` runtime type is `MutableList` so the cast will succeed. But changing the signature is cleaner. 
Let's see if we can just cast it. 
```kotlin
(arms as MutableList<Arm>).removeAll(suspiciousArms)
```
This requires no signature change and does exactly what we want.
