package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.classfile.model.PointcutCoordinateSeries

interface TspyPolyglotHost {
    suspend fun evaluatePython(source: String): PointcutCoordinateSeries
}
