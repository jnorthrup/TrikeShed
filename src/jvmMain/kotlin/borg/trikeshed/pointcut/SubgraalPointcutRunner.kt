package borg.trikeshed.pointcut

import borg.trikeshed.cursor.FieldSynapse
import borg.trikeshed.cursor.TypedefProductionSystem
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.ResourceLimits
import org.graalvm.polyglot.Source
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.graalvm.polyglot.management.ExecutionEvent
import org.graalvm.polyglot.management.ExecutionListener
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.io.OutputStream

class SubgraalPointcutRunner(
    statementLimit: Long = 10000,
    outStream: OutputStream = System.out,
    errStream: OutputStream = System.err,
    inStream: InputStream = System.`in`
) : AutoCloseable {

    private val _events = MutableSharedFlow<PointcutEvent>(extraBufferCapacity = 10000)
    val events: SharedFlow<PointcutEvent> = _events.asSharedFlow()

    private val armedPaths = ConcurrentHashMap.newKeySet<String>()

    fun arm(path: String) {
        armedPaths.add(path)
    }

    fun disarm(path: String) {
        armedPaths.remove(path)
    }

    /**
     * Note on LLVM / MLIR:
     * MLIR stays out of tree. The external lowering process requires:
     * `mlir-opt --convert-to-llvm | mlir-translate --mlir-to-llvmir | llvm-as`
     * which produces the `.bc` file that Sulong consumes.
     */
    private val context: Context = Context.newBuilder("python", "js", "llvm")
        .allowHostAccess(HostAccess.NONE)
        .allowNativeAccess(true)
        .allowHostClassLookup { false }
        .allowExperimentalOptions(true)
        .option("llvm.verifyBitcode", "false")
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
        .statements(true)
        .roots(true)
        .collectReturnValue(true)
        .sourceFilter { armedPaths.isEmpty() || it.path in armedPaths }
        .attach(context.engine)

    private fun handleEventEnter(event: ExecutionEvent) {
        handleEvent(event, true)
    }

    private fun handleEventReturn(event: ExecutionEvent) {
        handleEvent(event, false)
    }

    private fun handleEvent(event: ExecutionEvent, isEnter: Boolean) {
        val phase = if (isEnter) 0.toByte() else 1.toByte()

        val location = event.location
        val source = location?.source
        val languageId = source?.language ?: "python"

        val vmFacet = VmFacet.values().find { it.id == languageId } ?: VmFacet.GRAAL_PYTHON

        val rootName = event.rootName ?: "unknown"
        val pointcutEvent = PointcutEvent(
            vmFacet = vmFacet,
            coordinate = rootName,
            target = null,
            propertyName = "",
            newValue = if (!isEnter) event.returnValue?.takeIf { !it.isNull }?.toString() else null,
            seq = TypedefProductionSystem.synapseRing.nextSeq(),
            sourcePath = source?.path,
            line = location?.startLine ?: -1,
            column = location?.startColumn ?: -1,
            isRoot = event.isRoot
        )
        _events.tryEmit(pointcutEvent)

        val isWrite = (!isEnter) && (!event.isExpression || event.returnValue != null) && (!event.isRoot)
        val opcode = if (isWrite) FieldSynapse.OP_L_SET.toByte() else FieldSynapse.OP_L_GET.toByte()

        val methodIdx = TypedefProductionSystem.InternPool.intern(rootName)

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
            seq = pointcutEvent.seq,
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

    fun evalFile(language: String, file: File): org.graalvm.polyglot.Value {
        val source = Source.newBuilder(language, file).build()
        return context.eval(source)
    }

    override fun close() {
        listener.close()
        context.close()
    }
}
