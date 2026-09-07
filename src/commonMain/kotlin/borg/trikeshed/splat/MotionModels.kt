package borg.trikeshed.splat

import borg.trikeshed.context.SplatFanoutDispatcherElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.SplatChannelRunner
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.lib.α

/** Feature extractors for choreography layer contexts */
object MotionFeatures {
    /** ChannelRunner context: (componentName, pendingCount) → query features */
    fun channelRunnerQuery(context: Join<String, Int>): Series<Double> =
        8.j { i ->
            when (i) {
                0 -> context.b.toDouble()                           // pendingCount
                1 -> context.a.hashCode().toDouble() * 0.001       // component hash
                2 -> 0.0  // pendingOps (would need richer context)
                3 -> 0.0  // waiters
                else -> 0.0
            }
        }

    /** ChannelRunner context → transform features (same for now) */
    fun channelRunnerTransform(context: Join<String, Int>): Series<Double> =
        channelRunnerQuery(context)

    /** ChannelOutcome → target vector */
    fun channelOutcomeTarget(outcome: SplatChannelRunner.ChannelOutcome): Series<Double> =
        4.j { i ->
            when (outcome) {
                SplatChannelRunner.ChannelOutcome.SUBMIT_MORE -> if (i == 0) 1.0 else 0.0
                SplatChannelRunner.ChannelOutcome.WAIT_COMPLETE -> if (i == 1) 1.0 else 0.0
                SplatChannelRunner.ChannelOutcome.DRAIN -> if (i == 2) 1.0 else 0.0
                SplatChannelRunner.ChannelOutcome.ERROR -> if (i == 3) 1.0 else 0.0
            }
        }

    /** FanoutDispatcher context: (eventType, subscriberType) → query features */
    fun fanoutQuery(context: Join<String, String>): Series<Double> =
        8.j { i ->
            when (i) {
                0 -> context.a.hashCode().toDouble() * 0.001  // eventType hash
                1 -> context.b.hashCode().toDouble() * 0.001  // subscriberType hash
                else -> 0.0
            }
        }

    /** FanoutDispatcher context → transform features */
    fun fanoutTransform(context: Join<String, String>): Series<Double> =
        fanoutQuery(context)

    /** DeliveryOutcome → target vector */
    fun deliveryOutcomeTarget(outcome: SplatFanoutDispatcherElement.DeliveryOutcome): Series<Double> =
        5.j { i ->
            when (outcome) {
                SplatFanoutDispatcherElement.DeliveryOutcome.DELIVERED -> if (i == 0) 1.0 else 0.0
                SplatFanoutDispatcherElement.DeliveryOutcome.BACKPRESSURE -> if (i == 1) 1.0 else 0.0
                SplatFanoutDispatcherElement.DeliveryOutcome.DEFERRED -> if (i == 2) 1.0 else 0.0
                SplatFanoutDispatcherElement.DeliveryOutcome.DROPPED -> if (i == 3) 1.0 else 0.0
                SplatFanoutDispatcherElement.DeliveryOutcome.ERROR -> if (i == 4) 1.0 else 0.0
            }
        }

    /** ElementState transition context: (elementKey, fromState) → query features */
    fun elementStateQuery(context: Join<String, ElementState>): Series<Double> =
        8.j { i ->
            when (i) {
                0 -> context.a.hashCode().toDouble() * 0.001
                1 -> context.b.ordinal.toDouble()
                else -> 0.0
            }
        }

    fun elementStateTransform(context: Join<String, ElementState>): Series<Double> =
        elementStateQuery(context)

    fun elementStateTarget(state: ElementState): Series<Double> =
        5.j { if (it == state.ordinal) 1.0 else 0.0 }
}

/**
 * Factories for the models wired to the choreography layer.
 *
 * Carried back from `libs/motion-estimation` (deleted 2026-07-13 with the rest of `libs/`)
 * and adapted: master's [GaussianMotionModel] takes ONE projector, having dropped this
 * branch's `transformProjector`, `targetProjector` and `Config` (queryDim, transformDim,
 * lowRankDim, kNearest, initialUnits). Each factory therefore binds the query projector
 * alone. The transform and target projections survive as [MotionFeatures] functions but no
 * longer reach the model; restoring the richer model is a separate decision.
 */
object GaussianMotionModels {
    /** Model for [SplatChannelRunner] channel control decisions. */
    fun channelRunner(): GaussianMotionModel<Join<String, Int>, SplatChannelRunner.ChannelOutcome> =
        GaussianMotionModel(MotionFeatures::channelRunnerQuery)

    /** Model for the fanout dispatcher's delivery predictions. */
    fun fanoutDispatcher(): GaussianMotionModel<Join<String, String>, SplatFanoutDispatcherElement.DeliveryOutcome> =
        GaussianMotionModel(MotionFeatures::fanoutQuery)

    /** Model for [ElementState] lifecycle transitions. */
    fun elementState(): GaussianMotionModel<Join<String, ElementState>, ElementState> =
        GaussianMotionModel(MotionFeatures::elementStateQuery)
}
