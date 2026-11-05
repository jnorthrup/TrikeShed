package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.ModuleContext
import borg.trikeshed.userspace.nio.file.spi.*
import kotlinx.coroutines.*

/** Borrows the real corpus owner; no second retrieval algorithm, parser or route language. */
object CamelCorpusRoutingRunner {
    const val LATEST = "camel/corpus/latest"
    private const val REPORT_LIMIT = 4 * 1024 * 1024

    fun register(ctx: ModuleContext, owner: CamelRouteElement) {
        val process by lazy { CamelCorpusProcess.packaged(ctx.scope.coroutineContext[FileOperations] ?: JvmFileOperations()) }
        ctx.lcncRunners[CamelCorpusRouting.TYPE] = runner(ctx, owner) { process }
    }

    internal fun runner(ctx: ModuleContext, owner: CamelRouteElement, process: () -> CamelCorpusProcess): LcncNodeRunner =
        boundLcnc(owner, LcncNodeRunner { node, inputs ->
            require(node.params.keys.all { it in _s["receiptCid", "mode", "in:receiptCid", "in:mode"] } &&
                inputs.keys.all { it in _s["receiptCid", "mode"] }) { "Corpus routing accepts only receiptCid and explicit mode" }
            val receipt = ContentId(lcncInputValue(node, inputs, "receiptCid") as? String ?: error("Corpus receipt CID required"))
            val mode = lcncInputValue(node, inputs, "mode") as? String ?: error("Explicit corpus mode required")
            require(mode in _s["snapshot", "current"]) { "Explicit current or snapshot corpus mode required" }
            val bytes = ctx.casStore.getBounded(receipt, REPORT_LIMIT) ?: error("Retained corpus search receipt missing")
            require(ContentId.of(bytes) == receipt) { "Corpus receipt digest differs" }
            val retained = CanonicalCbor.decodeMapBounded(bytes, REPORT_LIMIT, maxDepth = 32, maxItems = 200_000)
            require(CanonicalCbor.encodeMap(retained).contentEquals(bytes)) { "Canonical corpus receipt required" }
            val source = retained["source"] as? Map<*, *> ?: error("Corpus source descriptor required")
            require(source["mode"] == mode && source["selected"] == "english-receipt-corpus") { "Choose the corpus receipt's exact source mode" }
            val search = retained["search"] as? Map<*, *> ?: error("Corpus receipt must contain an explicit lexical search")
            require(search["schema"] == "trikeshed.english-corpus-search.v1" && search["dataset"] == "english-selected-corpus-retrieval") {
                "Unsupported corpus search receipt"
            }
            val topics = retained["topics"] as? Map<*, *>
            val request = _m["receiptCids" j retained["receiptCids"], "mode" j mode, "topics" j topics?.get("config"),
                "query" j search["query"]] + if ("view" in retained) _m["view" j retained["view"]] else emptyMap()
            val corpus = checkNotNull(ctx.lcncRunners[EnglishCorpusContract.TYPE]) { "English corpus owner is unavailable" }
            suspend fun regenerate(): Map<String, Any?> = corpus.execute(LcncNode("checked-corpus", EnglishCorpusContract.TYPE), request).also {
                require(it["receiptCid"] == receipt.value) { "Corpus search or source changed; rebuild the selected corpus" }
            }
            val before = regenerate()
            val hits = (before["search"] as? Map<*, *>)?.get("hits") as? List<*> ?: error("Checked lexical hits required")
            val signal = if (hits.isEmpty()) CamelCorpusRouting.EMPTY else CamelCorpusRouting.MATCHES
            fun cid(key: String) = (before[key] as? String ?: error("Checked corpus identity required")).also { ContentId(it) }
            val corpusCid = cid("corpusCid"); val matrixCid = cid("matrixCid")
            val topicCid = before["topicCid"]?.let { require(it is String); ContentId(it); it }
            val workerRequest = _m["kind" j CamelCorpusProcess.PROTOCOL, "receiptCid" j receipt.value,
                "corpusCid" j corpusCid, "matrixCid" j matrixCid, "topicCid" j topicCid, "signal" j signal]
            val selected = checkNotNull(currentCoroutineContext()[CamelRouteElement])
            withContext(ctx.scope.coroutineContext.minusKey(Job)) { selected.open() }
            selected.command {
                selected.observe { ctx.blackboard.put(LATEST, _m["kind" j CamelCorpusRouting.KIND,
                    "outcome" j "admitted", "phase" j "ROUTE_EXECUTION", "postCheck" j "not-completed"], "camel") }
                var execution: Map<String, Any?>? = null
                var postCheck = "not-completed"
                try {
                    val carried = withContext(Dispatchers.IO) { process().execute(workerRequest) }
                    execution = carried.record
                    val lane = CamelCorpusRouting.checkedReply(carried.reply, receipt.value, corpusCid, matrixCid, topicCid, signal)
                    // Inside the admitted action: cancelling its waiter cannot skip the final check or record.
                    postCheck = "checking-source"
                    val after = regenerate()
                    postCheck = if (mode == "current") "current-source-rechecked" else "explicit-historical-snapshot-rechecked"
                    val terminal = retain(ctx, selected, _m["kind" j CamelCorpusRouting.KIND, "outcome" j "completed",
                        "postCheck" j postCheck, "receiptCid" j receipt.value, "mode" j mode, "lane" j lane,
                        "execution" j execution, "source" j after["source"], "atMs" j ctx.clock()])
                    _m["receiptCid" j receipt.value, "corpusCid" j corpusCid, "matrixCid" j matrixCid, "topicCid" j topicCid,
                        "lane" j lane, "source" j after["source"], "execution" j carried.record, "terminalCid" j terminal]
                } catch (failure: Throwable) {
                    if (failure is CamelCorpusProcess.Refused) execution = failure.record
                    if (failure is CamelCorpusProcess.Cancelled) execution = failure.record
                    if (postCheck == "checking-source") postCheck = "source-check-refused"
                    // A completed route is not a successful current-source result if regeneration refused.
                    val terminal = withContext(NonCancellable) { retain(ctx, selected,
                        _m["kind" j CamelCorpusRouting.KIND, "outcome" j "refused", "postCheck" j postCheck,
                            "receiptCid" j receipt.value, "mode" j mode, "phase" j "ROUTE_EXECUTION",
                            "execution" j execution, "error" j (failure.message ?: failure::class.java.name).take(2048), "atMs" j ctx.clock()]) }
                    if (failure is CancellationException) throw failure
                    throw IllegalStateException("Corpus route refused; terminal receipt $terminal", failure)
                }
            }
        })

    private suspend fun retain(ctx: ModuleContext, owner: CamelRouteElement, terminal: Map<String, Any?>): String {
        val bytes = CanonicalCbor.encodeMap(terminal)
        require(bytes.size <= 512 * 1024) { "Corpus route terminal record limit" }
        val cid = ctx.casStore.put(bytes)
        require(ContentId.of(bytes) == cid && ctx.casStore.getBounded(cid, 512 * 1024)?.contentEquals(bytes) == true) { "Corpus terminal CAS readback differs" }
        ctx.casStore.sync()
        owner.observe { ctx.blackboard.put(LATEST, _m["kind" j CamelCorpusRouting.KIND,
            "terminalCid" j cid.value, "outcome" j terminal["outcome"], "postCheck" j terminal["postCheck"]], "camel") }
        return cid.value
    }
}
