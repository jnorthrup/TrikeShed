package borg.trikeshed.vast

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.torrent.percentEncode
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.datetime.Clock

/** Vast's rental kinds change the quotes returned by the public marketplace. */
enum class VastTier(val wire: String) {
    ON_DEMAND("ondemand"), INTERRUPTIBLE("bid"), RESERVED("reserved"),
}

/** Marketplace observations only: a quote neither rents a GPU nor represents an inference endpoint. */
object VastCatalog {
    const val SOURCE = "https://console.vast.ai/api/v0/bundles/"
    const val RESPONSE_BYTES = 2_000_000
    const val REQUESTED_LIMIT = 1000
    /** Observed API cap, even when Vast reports truncated=false. It is not an inventory size. */
    const val OBSERVED_LIMIT = 64

    /** Each request owns bounded tier dispatch, fan-in and joined cleanup over the existing HTX element. */
    suspend fun search(gpu: String = "", timeoutMs: Long = 12_000): Map<String, Any?> = coroutineScope {
        require(gpu.length <= 100) { "GPU name is too long" }
        require(timeoutMs > 0) { "Vast request timeout must be positive" }
        val htx = currentCoroutineContext()[HtxKey] ?: error("No HTTP route available")
        val supervisor = SupervisorJob(currentCoroutineContext()[Job])
        val scope = CoroutineScope(currentCoroutineContext() + supervisor)
        val input = Channel<VastTier>(VastTier.entries.size)
        val output = Channel<Join<VastTier, Map<String, Any?>>>(VastTier.entries.size)
        val admitted = ArrayList<Job>(VastTier.entries.size)
        repeat(VastTier.entries.size) {
            admitted.add(scope.launch {
                for (tier in input) output.send(tier j fetch(htx, tier, gpu.trim(), timeoutMs))
            }.also { worker ->
                worker.invokeOnCompletion { failure -> if (failure != null) output.close(failure) }
            })
        }
        try {
            for (tier in VastTier.entries) input.send(tier)
            input.close()
            val results = mutableMapOf<VastTier, Map<String, Any?>>()
            repeat(VastTier.entries.size) { output.receive().let { results[it.a] = it.b } }
            for (worker in admitted) worker.join()
            output.close()
            supervisor.complete()
            supervisor.join()
            _m[
                "atMs" j Clock.System.now().toEpochMilliseconds(),
                "gpu" j gpu.trim(),
                "source" j SOURCE,
                "tiers" j VastTier.entries.map { results.getValue(it) },
            ]
        } catch (failure: Throwable) {
            input.cancel()
            output.cancel()
            supervisor.cancel(CancellationException("Vast catalog request stopped", failure))
            withContext(NonCancellable) { supervisor.join() }
            throw failure
        }
    }

    fun query(tier: VastTier, gpu: String): Map<String, Any?> = _m[
        "limit" j REQUESTED_LIMIT,
        "type" j tier.wire,
        "rentable" j _m["eq" j true],
        "rented" j _m["eq" j false],
        "order" j _l[_l["dph_total", "asc"]],
    ] + if (gpu.isBlank()) emptyMap() else _m["gpu_name" j _m["eq" j gpu.trim()]]

    suspend fun fetch(htx: HtxElement, tier: VastTier, gpu: String, timeoutMs: Long): Map<String, Any?> {
        val url = SOURCE + "?q=" + percentEncode(jsonOf(query(tier, gpu)))
        return try {
            EgressAllowlist.allowUrl(SOURCE)
            val request = parseHtxRequest(url, method = HtxMethod.GET).copy(
                timeoutMs = timeoutMs, maxResponseBytes = RESPONSE_BYTES,
                headers = htxHeaders("Accept" j "application/json"),
            )
            val response = withTimeout(timeoutMs) { htx.request(request) }
            check(response.status in 200..299) { "Vast returned HTTP ${response.status}" }
            val root = reifyStrict(response.body.toArray().decodeToString()) as? Map<*, *>
                ?: error("Invalid Vast response")
            val offers = root["offers"] as? List<*> ?: error("Vast response has no offers")
            require(offers.all { it is Map<*, *> }) { "Vast response contains an invalid offer" }
            // Keep the raw quote, including every tariff and unknown future field. Never make up a missing price.
            _m[
                "tier" j tier.wire,
                "offers" j offers,
                "truncated" j (root["truncated"] == true),
                "possiblyLimited" j (offers.size >= OBSERVED_LIMIT),
                "metadata" j root.filterKeys { it != "offers" },
            ]
        } catch (failure: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failed(tier, "Vast request timed out")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            failed(tier, failure.message ?: "Vast request failed")
        }
    }

    fun failed(tier: VastTier, reason: String): Map<String, Any?> =
        _m["tier" j tier.wire, "offers" j emptyList<Any>(), "error" j reason]
}
