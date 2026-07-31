import re

with open("src/commonMain/kotlin/borg/trikeshed/userspace/FunctionalUringFacade.kt", "r") as f:
    content = f.read()

# Instead of just running backend.batchEnqueue inside withContext(NonCancellable), we need to ensure completions are not dropped if cancelled.
# Oh, wait. If we `withContext(NonCancellable)`, the coroutine will complete without cancellation, but if the caller was cancelled, `withContext` will throw `CancellationException` and NOT return the result!
# Thus the caller drops the completions. We must store them internally.

# Let's check where `FunctionalUringFacade` stores completions:
# `private val completions = ArrayDeque<SelectionResult>()`
# We could store them there. BUT `batchEnqueue` returns a `Series<UringCompletion>`. If we store them in `completions`, they are `SelectionResult` and they are drained by `wait()`.
# If `batchEnqueue` is meant to be a standalone suspend function, then returning it but losing it on cancellation means we should probably add them to an internal buffer, OR just not return them directly but rather return the stashed completions.
# Let's store them in a buffer, or maybe we catch CancellationException and save them.

new_facade = """    // -- Unified API --

    private val stash = ArrayDeque<borg.trikeshed.userspace.UringCompletion>()

    suspend fun batchEnqueue(operations: Series<UringSubmission>): Series<UringCompletion> {
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            backend.batchEnqueue(operations)
        }
        
        // Stash the results in case of cancellation
        val list = mutableListOf<UringCompletion>()
        borg.trikeshed.lib.forEach(res) { list.add(it) }
        stash.addAll(list)
        
        // Now if we check for cancellation, it throws, but we safely stashed them!
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        
        // Return from stash if active
        val toReturn = stash.toList()
        stash.clear()
        return borg.trikeshed.lib.seriesOf<UringCompletion>(toReturn)
    }

    /** Enqueue a raw [UringSubmission]. */"""

old_facade = """    // -- Unified API --

    suspend fun batchEnqueue(operations: Series<UringSubmission>): Series<UringCompletion> = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        backend.batchEnqueue(operations)
    }

    /** Enqueue a raw [UringSubmission]. */"""

content = content.replace(old_facade, new_facade)
content = content.replace("import borg.trikeshed.userspace.UringCompletion", "import borg.trikeshed.userspace.UringCompletion\nimport kotlinx.coroutines.ensureActive")

with open("src/commonMain/kotlin/borg/trikeshed/userspace/FunctionalUringFacade.kt", "w") as f:
    f.write(content)

