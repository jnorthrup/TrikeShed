package borg.trikeshed.lcnc.ccek

import borg.trikeshed.context.nuid.*
import borg.trikeshed.htx.*
import borg.trikeshed.ipns.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncConfixControls
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyMap
import kotlinx.coroutines.currentCoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/** A remotely invocable, already registered construction; its version is never inferred. */
data class LcncDhtProgram(val program: String, val programCid: ContentId, val scope: String) {
    init {
        require(program.isNotBlank() && program.length <= 256 && program.none { it.isISOControl() })
        LcncConfixControls(subnet = scope)
    }
    fun toMap(): Map<String, String> = mapOf("program" to program, "programCid" to programCid.value, "scope" to scope)
}

/** Signed discovery names an HTTP host; DHT peers never receive model prompts or invocation inputs. */
class LcncDhtHost(origin: String, programs: Series<LcncDhtProgram>) {
    val origin: String = origin.removeSuffix("/")
    val programs: Series<LcncDhtProgram> = Array(programs.size) { programs[it] }.toSeries()
    init {
        require(this.origin.length <= 512 && ORIGIN.matches(this.origin)) { "Invalid LCNC host origin" }
        val target = parseHtxRequest(this.origin).target
        require(target.port in 1..65535 && target.requestPath == "/") { "Invalid LCNC host port or path" }
        require(target.scheme == HtxScheme.HTTPS || target.host in setOf("localhost", "127.0.0.1", "[::1]")) {
            "LCNC host requires HTTPS or loopback HTTP"
        }
        require(programs.size <= 32) { "LCNC host directory exceeds 32 registrations" }
        require(programs.view.map { it.program }.distinct().size == programs.size) { "Duplicate LCNC host registration" }
    }
    fun toMap(): Map<String, Any?> = mapOf("origin" to origin, "programs" to (programs α { it.toMap() }).toList())
    val bytes: ByteArray get() = jsonOf(mapOf("lcnc" to 1) + toMap()).encodeToByteArray().also {
        require(it.size <= 4096) { "LCNC host directory exceeds signed record capacity" }
    }
    val cid: ContentId get() = ContentId.of(bytes)

    companion object {
        private val ORIGIN = Regex("https?://(?:[A-Za-z0-9.-]+|\\[[0-9a-fA-F:]+])(?::[0-9]{1,5})?")
    }
}

data class LcncDhtDiscovery(val record: IpnsRecord, val host: LcncDhtHost) {
    fun toMap(): Map<String, Any?> = host.toMap() + mapOf(
        "name" to record.name.toString(), "hostCid" to host.cid.value,
        "sequence" to record.sequence.toString(), "validUntil" to record.validUntil.toString(),
    )
}

class LcncDhtFailure(val status: Int, val code: String, message: String) : IllegalStateException(message)

/**
 * IPNS supplies signed Kademlia discovery; the existing HTX and LCNC workgroup owners
 * supply transport and execution. Call through IpnsNode.request so publication,
 * resolution and invocation share its bounded admission and awaited drain.
 */
object LcncDht {
    fun snapshot(origin: String, registrations: Series<Join<Workgroup, Join<String, ContentId>>>): LcncDhtHost =
        LcncDhtHost(origin, registrations α { LcncDhtProgram(it.b.a, it.b.b, it.a.scope.toString()) })

    /** Identity multihash embeds a bounded raw block; this is a standard /ipfs path, not a new DHT record type. */
    fun path(host: LcncDhtHost): String = "/ipfs/" + IpnsCid(0x55u, IpnsEncoding.multihash(0u, host.bytes))

    fun decode(path: String): LcncDhtHost {
        require(path.startsWith("/ipfs/") && path.length <= IpnsRecord.MAX_BYTES) { "Not an LCNC host record" }
        val cid = IpnsCid.fromBytes(IpnsEncoding.unbase(path.removePrefix("/ipfs/"), IpnsRecord.MAX_BYTES))
        val (hash, bytes) = IpnsEncoding.multihashParts(cid.multihash, IpnsRecord.MAX_BYTES)
        require(cid.codec == 0x55uL && hash == 0uL && bytes.size <= 4096) { "LCNC host record must contain its directory" }
        val value = reifyMap(bytes)
        require(value.keys == setOf("lcnc", "origin", "programs") && (value["lcnc"] as? Number)?.toInt() == 1) {
            "Unsupported LCNC host directory"
        }
        val rows = requireNotNull(value["programs"] as? List<*>) { "Missing LCNC registrations" }
        require(rows.size <= 32)
        val host = LcncDhtHost(requireNotNull(value["origin"] as? String), rows.size j { i: Int ->
            val row = requireNotNull(rows[i] as? Map<*, *>)
            require(row.keys == setOf("program", "programCid", "scope")) { "Invalid LCNC registration fields" }
            LcncDhtProgram(requireNotNull(row["program"] as? String),
                ContentId(requireNotNull(row["programCid"] as? String)), requireNotNull(row["scope"] as? String))
        })
        require(bytes.contentEquals(host.bytes)) { "Noncanonical LCNC host directory" }
        return host
    }

    suspend fun publish(host: LcncDhtHost): Join<LcncDhtDiscovery, IpnsPublishReport> {
        val publisher = checkNotNull(currentCoroutineContext()[IpnsPublisher]) { "No IPNS publisher bound" }
        val report = publisher.publish(path(host))
        return LcncDhtDiscovery(report.record, host) j report
    }

    suspend fun resolve(name: IpnsName, now: Instant = Clock.System.now()): LcncDhtDiscovery? {
        val dht = checkNotNull(currentCoroutineContext()[IpnsDht]) { "No Kademlia DHT bound" }
        return dht.resolve(name, now)?.let { LcncDhtDiscovery(it, decode(it.value)) }
    }

    /** Re-resolve before admission, pin both host directory and construction, and send exactly once. */
    suspend fun invoke(
        name: IpnsName,
        hostCid: ContentId,
        program: String,
        programCid: ContentId,
        subnet: String,
        inputs: Map<String, Any?>,
    ): HtxResponse {
        val discovered = resolve(name) ?: throw LcncDhtFailure(404, "host_unavailable", "Signed host record is absent")
        if (discovered.host.cid != hostCid) throw LcncDhtFailure(409, "host_changed", "Resolve the changed host directory before selecting a construction")
        val selected = discovered.host.programs.view.singleOrNull { it.program == program && it.programCid == programCid }
            ?: throw LcncDhtFailure(409, "program_unavailable", "The selected program version is not advertised")
        LcncConfixControls(subnet = subnet)
        val scope = Subnet.parse(selected.scope)
        val target = Subnet.parse(subnet)
        if (!(scope contains target) || target.level - scope.level > 3)
            throw LcncDhtFailure(400, "scope_mismatch", "Requested subnet is outside the selected construction's scope")
        val htx = checkNotNull(currentCoroutineContext()[HtxKey]) { "No userspace HTX transport bound" }
        val request = jsonOf(mapOf("program" to program, "expectProgramCid" to programCid.value,
            "subnet" to subnet, "inputs" to inputs)).encodeToByteArray()
        require(request.size <= 1_048_576) { "LCNC network request exceeds one MiB" }
        val response = htx.request(parseHtxRequest(discovered.host.origin + "/api/lcnc/run",
            method = HtxMethod.POST, body = ByteSeries(request)).copy(timeoutMs = 300_000)
            .withHeader("Content-Type", "application/json"))
        val receipt = try { reifyMap(response.body.toArray()) } catch (_: Exception) {
            throw LcncDhtFailure(502, "remote_response_invalid", "Remote LCNC response is not a receipt object; execution outcome is unknown")
        }
        val envelope = mapOf("name" to name.toString(), "hostCid" to hostCid.value,
            "origin" to discovered.host.origin, "status" to response.status)
        val result = if (response.status !in 200..299 && !receipt.containsKey("receiptCid")) {
            val refusal = receipt["error"] in setOf("program_version_drift", "no_such_program", "no_workgroup",
                "workgroup_unavailable", "invalid_subnet", "reserved_case_input", "execution_capacity")
            envelope + mapOf("error" to if (refusal) "remote_refused" else "remote_outcome_unknown",
                "detail" to receipt["error"], "remote" to receipt)
        } else {
            if (!(receipt["program"] == program && receipt["programCid"] == programCid.value &&
                receipt["subnet"] == subnet && receipt["claimedAtSubnet"] == selected.scope &&
                (receipt["runId"] as? String)?.isNotBlank() == true &&
                (receipt["receiptCid"] as? String)?.let { runCatching { ContentId(it) }.isSuccess } == true))
                throw LcncDhtFailure(502, "receipt_mismatch", "Remote LCNC receipt does not match the selected invocation")
            envelope + mapOf("receipt" to receipt)
        }
        return HtxResponse(response.status, ByteSeries(jsonOf(result).encodeToByteArray()),
            htxHeaders("Content-Type" j "application/json"))
    }
}
