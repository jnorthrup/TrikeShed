package borg.trikeshed.pointcut

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.instrumentation.EventContext
import com.oracle.truffle.api.instrumentation.ExecutionEventNode
import com.oracle.truffle.api.instrumentation.ExecutionEventNodeFactory
import com.oracle.truffle.api.instrumentation.Instrumenter
import com.oracle.truffle.api.instrumentation.SourceSectionFilter
import com.oracle.truffle.api.instrumentation.StandardTags
import com.oracle.truffle.api.instrumentation.TruffleInstrument
import com.oracle.truffle.api.nodes.LanguageInfo

@TruffleInstrument.Registration(
    id = PolyglotPointcutInstrument.ID,
    name = "TrikeShed Pointcut Instrument",
    version = "1.0"
)
class PolyglotPointcutInstrument : TruffleInstrument() {

    override fun onCreate(env: Env) {
        val instrumenter: Instrumenter = env.instrumenter

        // Filter for any language that emits WriteVariableTag
        val filter = SourceSectionFilter.newBuilder()
            .tagIs(StandardTags.WriteVariableTag::class.java)
            .build()

        instrumenter.attachExecutionEventFactory(filter, PointcutEventNodeFactory())
    }

    class PointcutEventNodeFactory : ExecutionEventNodeFactory {
        override fun create(context: EventContext): ExecutionEventNode {
            return PointcutEventNode(context)
        }
    }

    class PointcutEventNode(private val context: EventContext) : ExecutionEventNode() {
        override fun onReturnValue(virtualFrame: com.oracle.truffle.api.frame.VirtualFrame, result: Any?) {
            // We get standard mutation tag. Try to derive the language, coordinate, and mutated value.
            val sourceSection = context.instrumentedSourceSection
            val language = sourceSection?.source?.language ?: "unknown"
            val coordinate = sourceSection?.characters?.toString() ?: "unknown"

            // To be precise we need property name, but WriteVariableTag often just writes to the current frame.
            // We will use the source section text as propertyName or coordinate for this simple trace.
            val propertyName = coordinate

            try {
                PointcutReporter.report(language, coordinate, null, propertyName, result)
            } catch (e: SecurityException) {
                // If vetoed, we should ideally abort the execution or log it.
                // Truffle requires exceptional control flow to abort.
                throw e
            }
        }
    }

    companion object {
        const val ID = "trikeshed-pointcut"
    }
}
