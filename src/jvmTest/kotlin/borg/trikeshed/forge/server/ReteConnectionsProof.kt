package borg.trikeshed.forge.server

import borg.trikeshed.dag.KifTee
import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.ontology.SumoCorpus
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.runBlocking

/** Controlled fixture through production matching, projection, index and HTTP routing. No daemon state is touched. */
object ReteConnectionsProof {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val network = ReteNetwork()
        val bank = KifKnowledgeBase()
        val corpus = SumoCorpus.text()
        check(corpus.isNotBlank()) { "Pinned SUMO resources are missing; run fetchSumoCorpus" }
        KifExpr.parseAll(corpus).forEach(bank::assert)
        val (tee, handle) = KifTee.attach(network, bank)
        try {
            val fixture = PlaneFacts.fact("evidence-proof", "submitted-job", linkedMapOf(
                "jobId" to "evidence-proof-job", "lifecycle" to "submitted", "dependencies" to emptyList<String>(),
                "revision" to 1L, "concept" to "AutonomousAgent",
            ))
            network.assert(fixture.factId, fixture.fields, fixture.versionCid, fixture.board)
            val receipt = network.admissionSnapshot().receipts.single()
            check(receipt.productionId == "job-dependency" && receipt.activation.ruleId == "start-job")
            check(receipt.delivery == "agenda-enqueued")
            check(receipt.activation.supportCids == listOf(fixture.versionCid))
            check(tee.projection(fixture.factId) == PlaneFacts.toKif(fixture))
            val wire = ReteWire(network, tee)
            val server = JvmKanbanServer(extraRoutes = listOf(wire::route))
            val response = server.routeHttp("GET /api/rete/connections HTTP/1.1\r\nHost: proof\r\n\r\n".encodeToByteArray())
            check(response.status == 200) { response.body }
            @Suppress("UNCHECKED_CAST")
            val data = JsonSupport.parse(response.body) as Map<String, Any?>
            println(JsonSupport.stringify(data + ("capture" to linkedMapOf(
                "kind" to "isolated-proof", "description" to "Synthetic submitted-job fact; real job-dependency production and pinned SUMO corpus. No job command was executed.",
                "corpusCid" to ContentId.of(corpus.encodeToByteArray()).value,
            ))))
        } finally {
            handle.close()
            network.close()
        }
    }
}
