package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.classfile.model.BytecodePointcutKind
import borg.trikeshed.classfile.model.PointcutCoordinate
import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.classfile.model.SourceCoordinate
import borg.trikeshed.classfile.model.SymbolCoordinate
import borg.trikeshed.lib.toSeries
import borg.trikeshed.pointcut.PointcutEvent
import borg.trikeshed.pointcut.SubgraalPointcutRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class TspyPolyglotHostImpl : TspyPolyglotHost {

    private fun mapEventToCoordinate(event: PointcutEvent): PointcutCoordinate {
        val synapse = event.toFieldSynapse()
        val isWrite = synapse.isSet || event.newValue != null || (event.coordinate.isNotEmpty() && !event.isRoot) // Fallback for assignments
        val kind = if (isWrite) BytecodePointcutKind.INSTANCE_FIELD_WRITE else BytecodePointcutKind.INSTANCE_FIELD_READ
        return PointcutCoordinate(
            kind = kind,
            jvmOpcode = "",
            bytecodeOffset = -1,
            source = SourceCoordinate(
                sourceFile = event.sourcePath ?: "unknown",
                line = event.line,
                column = event.column,
                language = event.vmFacet.id,
                bytecodeOffset = -1
            ),
            symbol = SymbolCoordinate(
                owner = "",
                name = event.coordinate,
                descriptor = "",
                methodName = event.coordinate,
                methodDescriptor = ""
            )
        )
    }

    override suspend fun evaluatePython(source: String): PointcutCoordinateSeries {
        return evaluateInternal("python", source)
    }

    override suspend fun evaluateJs(source: String): PointcutCoordinateSeries {
        return evaluateInternal("js", source)
    }

    private suspend fun evaluateInternal(language: String, source: String): PointcutCoordinateSeries {
        val collectedEvents = mutableListOf<PointcutEvent>()
        SubgraalPointcutRunner().use { runner ->
            val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
                runner.events.collect {
                    collectedEvents.add(it)
                }
            }
            runner.eval(language, source)
            yield()
            collectJob.cancelAndJoin()
        }
        val arr = Array(collectedEvents.size) { mapEventToCoordinate(collectedEvents[it]) }
        return arr.toSeries()
    }
}
