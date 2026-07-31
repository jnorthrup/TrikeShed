import re

with open("src/commonMain/kotlin/borg/trikeshed/userspace/FunctionalUringFacade.kt", "r") as f:
    content = f.read()

old_facade = """    // -- Unified API --

    suspend fun batchEnqueue(operations: Series<UringSubmission>): Series<UringCompletion> = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        backend.batchEnqueue(operations)
    }

    /** Enqueue a raw [UringSubmission]. */"""

new_facade = """    // -- Unified API --

    private val stash = ArrayDeque<borg.trikeshed.userspace.UringCompletion>()

    suspend fun batchEnqueue(operations: Series<UringSubmission>): Series<UringCompletion> {
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            backend.batchEnqueue(operations)
        }
        val list = mutableListOf<UringCompletion>()
        val it = borg.trikeshed.lib.toList(res).iterator()
        while (it.hasNext()) {
            list.add(it.next())
        }
        stash.addAll(list)
        
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        
        val toReturn = stash.toList()
        stash.clear()
        return borg.trikeshed.lib.seriesOf<UringCompletion>(toReturn)
    }

    /** Enqueue a raw [UringSubmission]. */"""

if "private val stash" not in content:
    content = content.replace(old_facade, new_facade)
    content = content.replace("import borg.trikeshed.userspace.UringCompletion", "import borg.trikeshed.userspace.UringCompletion\nimport kotlinx.coroutines.ensureActive\nimport borg.trikeshed.lib.toList")

with open("src/commonMain/kotlin/borg/trikeshed/userspace/FunctionalUringFacade.kt", "w") as f:
    f.write(content)
