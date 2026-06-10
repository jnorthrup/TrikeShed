package borg.trikeshed.splat

import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
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
    fun channelOutcomeTarget(outcome: ChannelRunner.ChannelOutcome): Series<Double> =
        4.j { i ->
            when (outcome) {
                ChannelRunner.ChannelOutcome.SUBMIT_MORE -> if (i == 0) 1.0 else 0.0
                ChannelRunner.ChannelOutcome.WAIT_COMPLETE -> if (i == 1) 1.0 else 0.0
                ChannelRunner.ChannelOutcome.DRAIN -> if (i == 2) 1.0 else 0.0
                ChannelRunner.ChannelOutcome.ERROR -> if (i == 3) 1.0 else 0.0
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
    fun deliveryOutcomeTarget(outcome: FanoutDispatcherElement.DeliveryOutcome): Series<Double> =
        5.j { i ->
            when (outcome) {
                FanoutDispatcherElement.DeliveryOutcome.DELIVERED -> if (i == 0) 1.0 else 0.0
                FanoutDispatcherElement.DeliveryOutcome.BACKPRESSURE -> if (i == 1) 1.0 else 0.0
                FanoutDispatcherElement.DeliveryOutcome.DEFERRED -> if (i == 2) 1.0 else 0.0
                FanoutDispatcherElement.DeliveryOutcome.DROPPED -> if (i == 3) 1.0 else 0.0
                FanoutDispatcherElement.DeliveryOutcome.ERROR -> if (i == 4) 1.0 else 0.0
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

/** Factory for creating NGS models wired to choreography layer */
object GaussianMotionModels {
    /** Model for ChannelRunner channel control decisions */
    fun channelRunner(): GaussianMotionModel<Join<String, Int>, ChannelRunner.ChannelOutcome> =
        GaussianMotionModel(
            queryProjector = MotionFeatures::channelRunnerQuery,
            transformProjector = MotionFeatures::channelRunnerTransform,
            targetProjector = MotionFeatures::channelOutcomeTarget,
            config = GaussianMotionModel.Config(
                queryDim = 8,
                transformDim = 4,
                lowRankDim = 2,
                kNearest = 8,
                initialUnits = 16,
            )
        )

    /** Model for FanoutDispatcher delivery predictions */
    fun fanoutDispatcher(): GaussianMotionModel<Join<String, String>, FanoutDispatcherElement.DeliveryOutcome> =
        GaussianMotionModel(
            queryProjector = MotionFeatures::fanoutQuery,
            transformProjector = MotionFeatures::fanoutTransform,
            targetProjector = MotionFeatures::deliveryOutcomeTarget,
            config = GaussianMotionModel.Config(
                queryDim = 8,
                transformDim = 5,
                lowRankDim = 2,
                kNearest = 8,
                initialUnits = 16,
            )
        )

    /** Model for ElementState lifecycle transitions */
    fun elementState(): GaussianMotionModel<Join<String, ElementState>, ElementState> =
        GaussianMotionModel(
            queryProjector = MotionFeatures::elementStateQuery,
            transformProjector = MotionFeatures::elementStateTransform,
            targetProjector = MotionFeatures::elementStateTarget,
            config = GaussianMotionModel.Config(
                queryDim = 8,
                transformDim = 5,
                lowRankDim = 2,
                kNearest = 8,
                initialUnits = 16,
            )
        )
}