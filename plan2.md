So `drainFail` is available.
Let's see the exact wording of the prompt:
"decode each batch arm's patches for swarm naming/lexical protocols before merge; isSuspicious arms quarantine instead of merge"
This implies we should filter out the suspicious arms before we merge them with Pijul, AND we need to quarantine them. 
To quarantine, we could call `drainFail(arm.session, "Quarantined by StigmergicProtocolDecoder: ${result.protocolName}")` inside `preflightPijulBatch` and NOT add it to `validArms` (so we re-assign `validArms` or filter it).
Wait, if we do this inside `preflightPijulBatch`, the returned list of arms (which won't include the quarantined ones) will be marked as successful `batchArms`. 
BUT! `drainExactArtifacts` does this:
```kotlin
        val pijulBatchResult = if (arms.size > 1) preflightPijulBatch(arms, baseSha) else null
        // ...
        val remainingArms = arms.filterNot { it in landed }
        for (arm in remainingArms) {
            val s = arm.session
            // ...
```
If we quarantine an arm in `preflightPijulBatch`, it WON'T be in `landed`. So it WILL be processed by the fallback `preflightExactPatch` sequentially!

To prevent it from being processed sequentially, we should either:
1. Not process it in `preflightPijulBatch`, but rather modify `arms` passed to `preflightPijulBatch` before it is called, or in `drainExactArtifacts` filter it out of `arms` before `preflightPijulBatch` and `preflightExactPatch` loop.
Wait, the prompt says "Wire StigmergicProtocolDecoder.decode into FlywheelDriver.preflightPijulBatch (~line 818)". It specifies exactly where to put it: inside `preflightPijulBatch`.
If it's inside `preflightPijulBatch`, how do we prevent the fallback loop from processing it? 

Let's look at `preflightPijulBatch` logic. 
What if we remove it from `arms` if `arms` is passed by reference? `arms` is a `List<Arm>`, not a `MutableList<Arm>`. We can't remove it from `arms`.
Wait! If we just call `drainFail`, does it stop the session from being processed? No, `drainFail` just records a failure, but the code still executes.
Wait! `isSuspicious arms quarantine instead of merge`
Maybe we just filter the `validArms`? But what about the fallback loop in `drainExactArtifacts`?
Let's look at `drainExactArtifacts` again.
