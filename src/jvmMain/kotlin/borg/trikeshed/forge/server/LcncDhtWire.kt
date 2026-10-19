package borg.trikeshed.forge.server

import borg.trikeshed.context.ElementState
import borg.trikeshed.ipns.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** The module's actual registration inventory and daemon-owned IPNS/HTX services. */
class LcncDhtWire(
    val node: IpnsNode?,
    val workgroups: LcncWorkgroupElement,
    val transport: CoroutineContext,
) {
    companion object {
        val paths = s_["status", "publish", "resolve", "run"] α { "/api/lcnc/network/$it" }
    }

    suspend fun route(method: String, path: String, text: String): HttpResponse? {
        val target = path.substringBefore('?')
        if (target !in paths) return null
        if (method != if (target.endsWith("/status")) "GET" else "POST")
            return json(405, mapOf("error" to "method_not_allowed"))
        return try {
            workgroups.refresh()
            if (target.endsWith("/status") && (node == null || node.lifecycleState != ElementState.ACTIVE))
                return json(200, mapOf("state" to "unavailable", "name" to null, "bootstrapPeers" to 0,
                    "registeredPrograms" to workgroups.registrations.size, "latest" to null, "error" to "network_unavailable"))
            val owner = node?.takeIf { it.lifecycleState == ElementState.ACTIVE }
                ?: return json(503, mapOf("error" to "network_unavailable"))
            val request = if (method == "GET") emptyMap() else {
                val body = text.substringAfter("\r\n\r\n", text)
                require(body.length <= 1_048_576) { "LCNC network request exceeds one MiB" }
                reifyMap(body)
            }
            fun required(key: String): String = requireNotNull((request[key] as? String)?.takeIf { it.isNotBlank() }) { "$key is required" }
            val host = if (target.endsWith("/publish")) LcncDht.snapshot(required("origin"), workgroups.registrations) else null
            owner.request {
                withContext(transport.minusKey(Job)) {
                    when (target.substringAfterLast('/')) {
                        "status" -> {
                            val publisher = checkNotNull(currentCoroutineContext()[IpnsPublisher])
                            val dht = checkNotNull(currentCoroutineContext()[IpnsDht])
                            val latest = publisher.latest?.let { record ->
                                LcncDhtDiscovery(record, LcncDht.decode(record.value)).toMap()
                            }
                            json(200, mapOf("state" to "ACTIVE", "name" to publisher.name.toString(),
                                "bootstrapPeers" to dht.bootstrap.size, "registeredPrograms" to workgroups.registrations.size,
                                "latest" to latest, "error" to publisher.lastFailure?.message))
                        }
                        "publish" -> {
                            val (discovery, report) = LcncDht.publish(checkNotNull(host))
                            json(if (report.complete) 200 else 502, discovery.toMap() + mapOf(
                                "complete" to report.complete, "acknowledgements" to report.acknowledgements.size,
                                "replicaTarget" to report.replicaTarget,
                            ))
                        }
                        "resolve" -> LcncDht.resolve(IpnsName.parse(required("name")))?.let { json(200, it.toMap()) }
                            ?: json(404, mapOf("error" to "host_unavailable"))
                        else -> {
                            val raw = requireNotNull(request["inputs"] as? Map<*, *>) { "inputs must be an object" }
                            require(raw.keys.all { it is String }) { "Input names must be strings" }
                            @Suppress("UNCHECKED_CAST") val inputs = raw as Map<String, Any?>
                            val response = LcncDht.invoke(IpnsName.parse(required("name")), ContentId(required("hostCid")),
                                required("program"), ContentId(required("programCid")), required("subnet"), inputs)
                            HttpResponse(response.status, response.body.toArray().decodeToString(), "application/json")
                        }
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: LcncDhtFailure) {
            json(failure.status, mapOf("error" to failure.code, "detail" to failure.message))
        } catch (failure: IllegalArgumentException) {
            json(400, mapOf("error" to "invalid_network_request", "detail" to failure.message))
        } catch (failure: Exception) {
            json(502, mapOf("error" to "network_failure", "detail" to failure.message))
        }
    }

    private fun json(status: Int, value: Map<String, Any?>): HttpResponse =
        HttpResponse(status, jsonOf(value), "application/json")
}
