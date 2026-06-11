package borg.trikeshed.graal

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.xvm.activejs.ccek.FieldSynapse
import org.xvm.activejs.ccek.PointcutEventProducer

/**
 * Host class to emit CCEK pointcut events from polyglot code.
 */
class PolyglotPointcutEmitter(private val producer: PointcutEventProducer) {

    @HostAccess.Export
    fun emit(
        phase: Byte,
        opcode: Byte,
        methodIdx: Int,
        addr: Int,
        seq: Int,
        timestamp: Long,
        callsiteHash: Int,
        templateIdx: Int
    ) {
        val synapse = FieldSynapse(
            phase = phase,
            opcode = opcode,
            methodIdx = methodIdx,
            addr = addr,
            seq = seq,
            nano = timestamp,
            callsiteHash = callsiteHash,
            templateIdx = templateIdx
        )
        producer.emit(synapse)
    }
}

/**
 * Evaluates code in a Context while exposing a PointcutEventProducer to it.
 */
fun Context.evalWithPointcuts(language: String, source: String, producer: PointcutEventProducer): Any? {
    val emitter = PolyglotPointcutEmitter(producer)

    // Bind the emitter to the context so polyglot scripts can access it
    this.getBindings(language).putMember("pointcutEmitter", emitter)

    return this.eval(language, source)?.asHostObject()
}
