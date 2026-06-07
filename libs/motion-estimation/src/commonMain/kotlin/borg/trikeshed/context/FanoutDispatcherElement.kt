package borg.trikeshed.context

import borg.trikeshed.chronicle.Chronicle
import borg.trikeshed.chronicle.FanoutSplat
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.toSeries
import borg.trikeshed.splat.Splat
import borg.trikeshed.splat.SplatModel
import borg.trikeshed.splat.toChronology

class FanoutDispatcherElement : SplatAsyncContextElement() {
    companion object Key : AsyncContextKey<FanoutDispatcherElement>()

    override val key: kotlin.coroutines.CoroutineContext.Key<*> get() = Key

    override val fanoutSubscribers: MutableList<AsyncContextElement> = mutableListOf()

    var fanoutSplatModel: SplatModel<Join<String, String>, DeliveryOutcome>? = null

    enum class DeliveryOutcome { DELIVERED, BACKPRESSURE, DEFERRED, DROPPED, ERROR }

    suspend fun <T> splatPublish(completion: T) {
        val eventType = completion!!::class.simpleName ?: "unknown"

        val actuals = mutableListOf<Join<String, DeliveryOutcome>>()
        val splats = mutableListOf<Join<String, Splat<DeliveryOutcome>>>()

        fanoutSubscribers.forEach { sub ->
            val subscriberType = sub::class.simpleName ?: "unknown"
            val context = eventType.j(subscriberType)

            val splat = fanoutSplatModel?.predict(context)
            splats.add(subscriberType.j(splat ?: emptySplat()))

            val outcome = tryDeliver(sub, completion)

            actuals.add(subscriberType.j(outcome))

            // fanoutSplatModel?.observe(context, outcome) // EmpircalMotionModel handles this natively
        }

        Chronicle.emit(
            FanoutSplat(
                dispatcherId = this.hashCode(),
                eventType = eventType,
                splats = actuals.size.j { i -> splats[i] },
                actuals = actuals.size.j { i -> actuals[i] },
                subscriberCount = fanoutSubscribers.size
            )
        )
    }

    private suspend fun tryDeliver(sub: AsyncContextElement, completion: Any): DeliveryOutcome {
        return try {
            if (sub.lifecycleState != ElementState.ACTIVE) return DeliveryOutcome.DEFERRED
            // ... actual delivery logic
            DeliveryOutcome.DELIVERED
        } catch (e: Exception) {
            if (e.message?.contains("Backpressure") == true) {
                DeliveryOutcome.BACKPRESSURE
            } else {
                DeliveryOutcome.ERROR
            }
        }
    }

    override fun captureComposition(): Join<String, Series<String>> =
        "FanoutDispatcher".j(fanoutSubscribers.size.j { i: Int -> "sub_$i" })

    private fun <T> emptySplat(): Splat<T> = 0.j { _ : Int -> error("empty") }
}
