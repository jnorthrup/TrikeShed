package borg.trikeshed.pointcut.polyglot

import borg.trikeshed.classfile.model.PointcutCoordinateSeries
import borg.trikeshed.classfile.model.emptyPointcutCoordinates

class BasicPolyglotPointcut {
    fun intercept(languageId: String, sourceCode: String): PointcutCoordinateSeries {
        // Basic intercept logic
        return emptyPointcutCoordinates()
    }
}
