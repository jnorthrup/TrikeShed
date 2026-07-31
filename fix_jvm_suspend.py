import re

with open("src/jvmMain/kotlin/borg/trikeshed/userspace/UserspaceIO.jvm.kt", "r") as f:
    content = f.read()

# Make the JVM batchEnqueue use runInterruptible or runBlocking in a way that is non-blocking suspend?
# I/O dispatchers?
# "verify that sqe submission and cqe harvesting are suspend not blocking" -> we should probably use kotlinx.coroutines.runInterruptible or withContext(Dispatchers.IO) 
new_method = """    override suspend fun batchEnqueue(submissions: Series<UringOp.Companion.UringSubmission>): Series<UringCompletion> {
        val subs = mutableListOf<UringOp.Companion.UringSubmission>()
        for (op in submissions.toList()) {
            subs.add(op)
        }
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            submitBatch(subs)
        }
        val comps = mutableListOf<UringCompletion>()
        for (r in res) {
            comps.add(UringCompletion(r.userData, r.res, 0))
        }
        return seriesOf<UringCompletion>(comps)
    }
}"""
content = re.sub(r"    override suspend fun batchEnqueue\(submissions: Series<UringOp\.Companion\.UringSubmission>\): Series<UringCompletion> \{.*?return seriesOf<UringCompletion>\(comps\)\n    }\n}", new_method, content, flags=re.DOTALL)

with open("src/jvmMain/kotlin/borg/trikeshed/userspace/UserspaceIO.jvm.kt", "w") as f:
    f.write(content)
