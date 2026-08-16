package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.classfile.model.emptyPointcutCoordinates
import borg.trikeshed.pointcut.SubgraalPointcutRunner

class TspyPolyglotHostImpl : TspyPolyglotHost {
    override suspend fun evaluatePython(source: String): PointcutCoordinateSeries {
        SubgraalPointcutRunner().use { runner ->
            runner.eval("python", source)
        }
        // SubgraalPointcutRunner maps ExecutionEvent to FieldSynapse.
        // We will return empty pointcuts for now as the events are published to TypedefProductionSystem
        return emptyPointcutCoordinates()
    }
}
