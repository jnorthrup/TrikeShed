package borg.trikeshed.lcnc.ccek

import borg.trikeshed.context.nuid.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/** The registered geometry and routed claim carried by every node in this invocation. */
typealias LcncWorkgroup = Join<Workgroup, Join<NuidFanoutElement.Claim, ContentId>>
typealias LcncWorkgroupArguments = Join<Map<String, Any?>, CoroutineContext>
typealias LcncWorkgroupResult = Join<ContentId, LcncRunner.ScopeResult>

object LcncWorkgroupKey : LcncServiceKey<LcncWorkgroup>("LcncWorkgroupKey")

/** Register one frozen construction; each accepted claim gets its own walker and joined assembly. */
suspend fun CcekReactorBinding.registerLcncWorkgroup(
    fanout: NuidFanoutElement,
    workgroup: Workgroup,
    program: LcncProgram,
    runners: Map<String, LcncNodeRunner>,
): ContentId {
    require(!program.controls.inspectionOnly) { "Inspection-only construction cannot process workgroup claims" }
    val source = LcncProgramConfix.toJson(program)
    val frozen = LcncProgramConfix.fromJson(program.name, source)
    fun requireInline(nodes: Series<LcncNode>) {
        for (node in nodes.view) {
            if (node.children.size > 0) requireInline(node.children)
            else require(node.subprogram == null &&
                (node.type != LcncContracts.SCOPE || node.params["program"].isNullOrBlank())) {
                "${node.id}: named constructions require a pinned program loader"
            }
        }
    }
    requireInline(frozen.nodes)
    val cid = ContentId.of(source.encodeToByteArray())
    val registry = runners.toMap()
    fanout.register(workgroup) { claim ->
        val payload = requireNotNull(claim.payload as? Join<*, *>) { "LCNC workgroup arguments are required" }
        val context = requireNotNull(payload.b as? CoroutineContext) { "LCNC workgroup invocation context is required" }
        val raw = requireNotNull(payload.a as? Map<*, *>) { "LCNC workgroup arguments must be a map" }
        require(raw.keys.all { it is String }) { "LCNC workgroup argument names must be strings" }
        @Suppress("UNCHECKED_CAST")
        val args = raw as Map<String, Any?>
        val selected = checkNotNull(currentCoroutineContext()[NuidInvocation]) { "LCNC workgroup invocation is absent" }
        val invocation = workgroup j (claim j cid)
        val result = LcncCcekAssembly(this, LcncRunner(registry)).launch(
            "${workgroup.name}/${claim.claimId}", frozen, args,
            context = currentCoroutineContext() + context.minusKey(Job) + fanout + LcncWorkgroupKey(invocation) + selected.context,
        ).result.await()
        cid j result
    }
    return cid
}

/** Completion includes the actual scope yields and drained receipt node, beyond claim admission. */
suspend fun NuidFanoutElement.runLcncWorkgroup(
    request: Nuid,
    args: Map<String, Any?> = emptyMap(),
): Join<NuidFanoutElement.DispatchResult, LcncWorkgroupResult> {
    val payload: LcncWorkgroupArguments = args.toMap() j currentCoroutineContext().minusKey(Job)
    val dispatched = dispatch(request, payload)
    check(dispatched.winner != null) { "No LCNC workgroup accepted the request" }
    val completion = checkNotNull(dispatched.completion) { "Workgroup accepted without an executor" }
    val result = checkNotNull(completion.await() as? Join<*, *>) { "Workgroup did not return an LCNC result" }
    val cid = checkNotNull(result.a as? ContentId) { "Workgroup did not identify its frozen construction" }
    val scope = checkNotNull(result.b as? LcncRunner.ScopeResult) { "Workgroup did not return an LCNC scope result" }
    return dispatched j (cid j scope)
}
