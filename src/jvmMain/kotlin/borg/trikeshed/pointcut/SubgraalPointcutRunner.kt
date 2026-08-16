package borg.trikeshed.pointcut

import borg.trikeshed.cursor.FieldSynapse
import borg.trikeshed.cursor.TypedefProductionSystem
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.management.ExecutionEvent
import org.graalvm.polyglot.management.ExecutionListener
import java.io.InputStream
import java.io.OutputStream

class SubgraalPointcutRunner(
    statementLimit: Long = 10000,
    outStream: OutputStream = System.out,
    errStream: OutputStream = System.err,
    inStream: InputStream = System.`in`
) : AutoCloseable {

    private val context: Context = Context.newBuilder("python", "js")
        .allowHostAccess(HostAccess.NONE)
        .allowHostClassLookup { false }
        .out(outStream)
        .err(errStream)
        .`in`(inStream)
        .resourceLimits(
            ResourceLimits.newBuilder()
                .statementLimit(statementLimit, null)
                .build()
        )
        .build().apply {
            getBindings("python").putMember("java_trikeshed_publish", org.graalvm.polyglot.proxy.ProxyExecutable { args ->
                // opcode: Byte, typedefName: String, methodName: String, siteIdx: Int, depth: Byte, isAfter: Boolean
                val opcode = args[0].asByte()
                val typedefName = args[1].asString()
                val methodName = args[2].asString()
                val siteIdx = args[3].asInt()
                val depth = args[4].asByte()
                val isAfter = args[5].asBoolean()
                TypedefProductionSystem.publish(opcode, typedefName, methodName, siteIdx, depth, isAfter)
                null
            })
        }

    private val listener = ExecutionListener.newBuilder()
        .onEnter(::handleEventEnter)
        .onReturn(::handleEventReturn)
        .expressions(true)
        .attach(context.engine)

    private fun handleEventEnter(event: ExecutionEvent) {
        handleEvent(event, true)
    }

    private fun handleEventReturn(event: ExecutionEvent) {
        handleEvent(event, false)
    }

    private fun handleEvent(event: ExecutionEvent, isEnter: Boolean) {
        val phase = if (isEnter) 0.toByte() else 1.toByte()

        // Since we are restricted to Polyglot ExecutionListener and cannot reliably filter or map
        // AST tags directly without Truffle API integration (which is not configured for this project),
        // we'll record generic expression evaluation events as L_GET to avoid fragile string parsing heuristics.

        val opcode = FieldSynapse.OP_L_GET.toByte()

        val name = event.rootName ?: "expr"
        val methodIdx = TypedefProductionSystem.InternPool.intern(name)

        val templateIdx = when (opcode.toInt() and 0xFF) {
            FieldSynapse.OP_L_GET -> if (isEnter) FieldSynapse.TPL_BEFORE_GET else FieldSynapse.TPL_AFTER_GET
            FieldSynapse.OP_L_SET -> if (isEnter) FieldSynapse.TPL_BEFORE_SET else FieldSynapse.TPL_AFTER_SET
            FieldSynapse.OP_P_GET -> if (isEnter) FieldSynapse.TPL_BEFORE_GET else FieldSynapse.TPL_AFTER_GET
            FieldSynapse.OP_P_SET -> if (isEnter) FieldSynapse.TPL_BEFORE_SET else FieldSynapse.TPL_AFTER_SET
            else -> FieldSynapse.TPL_BEFORE_GET
        }

        val callsiteHash = TypedefProductionSystem.callsiteHash(opcode, methodIdx, 0)
        val tm = System.nanoTime()

        val synapse = FieldSynapse(
            phase = phase,
            opcode = opcode,
            methodIdx = methodIdx,
            addr = 0,
            seq = 0,
            nano = tm,
            callsiteHash = callsiteHash,
            templateIdx = templateIdx
        )
        TypedefProductionSystem.publish(synapse)
    }

    fun eval(language: String, sourceCode: String): org.graalvm.polyglot.Value {
        val source = Source.newBuilder(language, sourceCode, "eval.\$language").build()
        return context.eval(source)
    }

    override fun close() {
        listener.close()
        context.close()
    }
}
