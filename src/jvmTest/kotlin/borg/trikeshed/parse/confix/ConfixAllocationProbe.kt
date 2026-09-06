package borg.trikeshed.parse.confix

import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import java.lang.management.ManagementFactory

object ConfixAllocationProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val payload = (0 until 32).joinToString(prefix = "{", postfix = "}") {
            "\"key$it\":\"${"x".repeat(128)}\""
        }.encodeToByteArray().toSeries()
        val iterations = args.firstOrNull()?.toInt() ?: 2000
        require(iterations > 0)
        var checksum = 0L
        fun measure(name: String, operation: () -> Int) {
            repeat(iterations) { checksum += operation() }
            val threadId = Thread.currentThread().threadId()
            val bytesBefore = bean.getThreadAllocatedBytes(threadId)
            val started = System.nanoTime()
            repeat(iterations) { checksum += operation() }
            val elapsed = System.nanoTime() - started
            val allocated = bean.getThreadAllocatedBytes(threadId) - bytesBefore
            println("$name bytes/op=${allocated.toDouble() / iterations} ns/op=${elapsed.toDouble() / iterations}")
        }
        measure("scan-and-first-value") {
            val document = confixDoc(payload, Syntax.JSON)
            (document.value("key0") as String).length
        }
        measure("scan-and-all-hashes") {
            val ids = Syntax.JSON.scanIndex(payload).facet(ConfixIndexK.StructuralNodes)
            var length = 0
            for (i in 0 until ids.size) length += requireNotNull(ids[i]).length
            length
        }
        println("checksum=$checksum")
    }
}
