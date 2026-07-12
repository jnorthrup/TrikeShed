package borg.trikeshed.pointcut

import borg.trikeshed.graal.ConfixBlackboard

object PointcutBlackboardIntegration {
    fun setup(blackboard: ConfixBlackboard) {
        PointcutReporter.blackboardCallback = { event ->
            val payload = mapOf(
                "vmFacet" to event.vmFacet.id,
                "coordinate" to event.coordinate,
                "target" to event.target.toString(),
                "propertyName" to event.propertyName,
                "newValue" to event.newValue.toString(),
                "timestamp" to event.timestamp
            )
            blackboard.put("pointcut/${event.vmFacet.id}/${event.timestamp}", payload, event.vmFacet.id)
        }
    }
}
