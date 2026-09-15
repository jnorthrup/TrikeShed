package borg.trikeshed.lcnc

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** JSON records at the application boundary; references always include an immutable version. */
typealias HeadhunterRecord = Map<String, Any?>
typealias HeadhunterReference = Twin<String>

/** The host owns the existing CAS and append log. This store owns no jobs or file lifetime. */
class HeadhunterStore(
    val board: ConfixBlackboard,
    val cas: CasStore,
    val log: DurableAppendLog,
    val clock: () -> Long,
) {
    private val lock = Mutex()
    private val heads = linkedMapOf<String, HeadhunterRecord>()
    private val versions = HashMap<String, HeadhunterRecord>()
    private var sequence = 0L
    private var restored = false
    private var failed: Throwable? = null

    suspend fun restore(): Int = lock.withLock { restoreLocked() }

    /** A stale writer cannot replace a newer head. Repeated artifact creation retains saved corrections. */
    suspend fun save(kind: String, id: String?, baseCid: String?, fields: Map<String, Any?>): HeadhunterRecord = lock.withLock {
        ready()
        saveLocked(kind, id, baseCid, fields)
    }

    /** Local originals reuse their retained evidence. Source paths do not revise that evidence. */
    suspend fun capture(
        kind: String,
        sourceFields: HeadhunterRecord,
        recordFields: HeadhunterRecord?,
        sourceId: String? = null,
        sourceBaseCid: String? = null,
        id: String? = null,
        baseCid: String? = null,
    ): Join<HeadhunterRecord, HeadhunterRecord?> = lock.withLock {
        ready()
        require(kind == "evidence" || kind == "listing") { "Capture requires evidence or a listing" }
        var source = sourceId?.let { requireRecord(it, "source") }
        val originalCid = (sourceFields["originalCid"] as? String)?.takeIf { original ->
            original.isNotBlank() && kind == "evidence" && sourceFields["origin"] == "upload" &&
                source?.let { localSource(it, original) } != false
        }
        var retained: HeadhunterRecord? = null
        if (originalCid != null) {
            require(recordFields == null || recordFields["originalCid"] == originalCid) { "Evidence must refer to its captured original" }
            // Ledger insertion order keeps existing identities stable, including after replay.
            for (record in heads.values) {
                if (record["kind"] != "evidence" || fieldsOf(record)["originalCid"] != originalCid) continue
                val owner = (fieldsOf(record)["sourceId"] as? String)?.let { heads[it] } ?: continue
                if (!localSource(owner, originalCid)) continue
                if (sourceId != null && owner["id"] != sourceId) continue
                if (id != null && record["id"] != id) continue
                source = owner
                retained = record
                break
            }
            if (source == null) source = heads.values.firstOrNull { localSource(it, originalCid) }
        }
        val existingSource = source
        val savedSource = if (originalCid != null && existingSource != null) {
            saveLocked("source", existingSource["id"] as String, sourceBaseCid ?: existingSource["cid"] as String,
                sourcePaths(fieldsOf(existingSource), sourceFields))
        } else saveLocked("source", sourceId, sourceBaseCid, sourcePaths(sourceFields, emptyMap()))
        val record = when {
            recordFields == null -> null
            retained != null && id == null && baseCid == null -> retained
            else -> saveLocked(kind, id, baseCid, recordFields + mapOf("sourceId" to savedSource["id"], "sourceCid" to savedSource["cid"]))
        }
        savedSource j record
    }

    private fun localSource(record: HeadhunterRecord, originalCid: String): Boolean =
        record["kind"] == "source" && fieldsOf(record)["origin"] == "upload" && fieldsOf(record)["originalCid"] == originalCid

    private fun sourcePaths(previous: HeadhunterRecord, incoming: HeadhunterRecord): HeadhunterRecord {
        val paths = SeriesBuffer<String>()
        fun add(value: Any?) {
            if (value is String && value.isNotBlank() && value !in paths.snapshot()) paths.add(value)
        }
        fun append(fields: HeadhunterRecord) {
            add(fields["relativePath"])
            for (path in (fields["relativePaths"] as? Iterable<*>) ?: emptyList<Any?>()) add(path)
        }
        append(previous)
        val count = paths.size
        append(incoming)
        return if (paths.size == count) previous else previous + mapOf("relativePath" to paths[0], "relativePaths" to paths.drain())
    }

    private suspend fun saveLocked(kind: String, id: String?, baseCid: String?, fields: HeadhunterRecord): HeadhunterRecord {
        require(kind in KINDS) { "Unknown headhunter record kind: $kind" }
        val encoded = canonical(if (kind == "action") actionFields(id, fields) else fields)
        val nextFields = JsonSupport.parseMap(encoded)
        val key = id ?: "$kind/${ContentId.of("$kind\n$encoded".encodeToByteArray()).hex}"
        require(ID.matches(key) && key.substringBefore('/') == kind) { "Record id must belong to $kind" }
        val old = heads[key]
        require(old != null || baseCid == null) { "No version to update for $key" }
        if ((kind == "artifact" || kind == "profile") && id == null && baseCid == null && old != null) {
            publish(old)
            return old
        }
        if (old != null && canonical(fieldsOf(old)) == encoded) {
            require(baseCid == null || baseCid == old["cid"] || versions[baseCid]?.get("id") == key) { "Unrelated baseCid for $key" }
            publish(old)
            return old
        }
        require(old == null || old["cid"] == baseCid) { "Stale baseCid for $key; read its current version" }
        withContext(fileIoContext) { validate(kind, key, nextFields, old) }
        return withContext(fileIoContext + NonCancellable) {
            val body = linkedMapOf<String, Any?>(
                "id" to key, "kind" to kind, "previousCid" to old?.get("cid"),
                // Confix's JSON number representation is Double, including replayed timestamps.
                "updatedAtMs" to clock().toDouble(), "fields" to nextFields,
            )
            val cid = cas.put(canonical(body).encodeToByteArray()).value
            val next = sequence + 1
            try {
                check(log.append(next, canonical(mapOf("id" to key, "cid" to cid)).encodeToByteArray()) == next) {
                    "Headhunter ledger sequence mismatch"
                }
                log.flush()
            } catch (failure: Throwable) {
                failed = failure
                throw failure
            }
            val record = body + ("cid" to cid)
            sequence = next
            versions[cid] = record
            heads[key] = record
            publish(record)
            record
        }
    }

    suspend fun records(): Series<HeadhunterRecord> = lock.withLock {
        ready()
        SeriesBuffer<HeadhunterRecord>(heads.size).apply { for (record in heads.values) add(record) }.drain()
    }

    suspend fun record(id: String): HeadhunterRecord? = lock.withLock { ready(); heads[id] }

    suspend fun version(id: String, cid: String): HeadhunterRecord? = lock.withLock {
        ready()
        versions[cid]?.takeIf { it["id"] == id }
    }

    /** Oldest first. The returned Series is detached from subsequent appends. */
    suspend fun history(id: String): Series<HeadhunterRecord> = lock.withLock {
        ready()
        val result = SeriesBuffer<HeadhunterRecord>()
        var record = heads[id]
        while (record != null) {
            result.add(record)
            record = (record["previousCid"] as? String)?.let { versions.getValue(it) }
        }
        val reverse = result.drain()
        reverse.size j { reverse[reverse.size - it - 1] }
    }

    /** Exact relationships and identifiers produce review candidates, never identity assertions. */
    suspend fun collisions(applicationId: String): Series<HeadhunterRecord> = lock.withLock {
        ready()
        val application = requireRecord(applicationId, "application")
        val app = fieldsOf(application)
        val listing = requireRecord(required(app, "listingId"), "listing")
        val listingFields = fieldsOf(listing)
        val contact = (app["contactId"] as? String)?.let { heads[it] }
        val result = SeriesBuffer<HeadhunterRecord>()

        fun evidence(left: HeadhunterRecord, right: HeadhunterRecord, field: String, value: Any?): HeadhunterRecord = mapOf(
            "field" to field, "value" to value,
            "sources" to listOf(reference(left), reference(right)),
        )

        fun listingMatch(other: HeadhunterRecord): Join<String, HeadhunterRecord>? {
            val fields = fieldsOf(other)
            if (other["id"] == listing["id"]) return "same-listing" j evidence(listing, other, "id", listing["id"])
            val url = (listingFields["url"] as? String)?.trim()?.substringBefore('#').orEmpty()
            if (url.isNotEmpty() && url == (fields["url"] as? String)?.trim()?.substringBefore('#'))
                return "same-listing-url" j evidence(listing, other, "url", url)
            val employerId = listingFields["employerId"] as? String
            val sameEmployer = if (!employerId.isNullOrBlank()) employerId == fields["employerId"]
                else normalize(listingFields["employer"] as? String).let { it.isNotEmpty() && it == normalize(fields["employer"] as? String) }
            val requisition = normalize(listingFields["requisition"] as? String)
            if (sameEmployer && requisition.isNotEmpty() && requisition == normalize(fields["requisition"] as? String))
                return "same-employer-requisition" j evidence(listing, other, "employer/requisition", listOf(employerId ?: listingFields["employer"], listingFields["requisition"]))
            return null
        }

        fun sameContact(other: HeadhunterRecord?): Join<String, HeadhunterRecord>? {
            if (contact == null || other == null) return null
            if (contact["id"] == other["id"]) return "same-contact" j evidence(contact, other, "id", contact["id"])
            val email = normalize(fieldsOf(contact)["email"] as? String)
            if (email.isNotEmpty() && email == normalize(fieldsOf(other)["email"] as? String))
                return "same-contact-email" j evidence(contact, other, "email", email)
            return null
        }

        fun add(other: HeadhunterRecord, match: Join<String, HeadhunterRecord>) {
            result.add(mapOf(
                "id" to other["id"], "cid" to other["cid"], "kind" to other["kind"],
                "reason" to match.a, "evidence" to listOf(match.b), "record" to other,
            ))
        }

        for (other in heads.values) {
            if (other["id"] == applicationId) continue
            val fields = fieldsOf(other)
            when (other["kind"]) {
                "listing" -> if (other["id"] != listing["id"]) listingMatch(other)?.let { add(other, it) }
                "application" -> {
                    val candidate = (fields["listingId"] as? String)?.let { heads[it] }
                    val match = candidate?.let(::listingMatch)
                        ?: sameContact((fields["contactId"] as? String)?.let { heads[it] })
                    if (match != null) add(other, match)
                }
                "representation" -> if (fields["status"] !in setOf("withdrawn", "ended", "released", "expired")) {
                    val candidate = (fields["listingId"] as? String)?.let { heads[it] }
                    val match = candidate?.let(::listingMatch)
                        ?: (fields["employerId"] as? String)?.takeIf { it == listingFields["employerId"] }?.let {
                            "employer-representation" j evidence(listing, other, "employerId", it)
                        }
                    if (match != null) add(other, match)
                }
                "action" -> {
                    val owner = (fields["applicationCid"] as? String)?.let { versions[it] }
                    val candidate = (fields["listingCid"] as? String)?.let { versions[it] }
                    val match = if (owner?.get("id") == applicationId)
                        "action-history" j evidence(application, other, "applicationId", applicationId)
                    else candidate?.let(::listingMatch)
                        ?: sameContact((fields["contactCid"] as? String)?.let { versions[it] })
                    if (match != null) add(other, match)
                }
            }
        }
        result.drain()
    }

    private suspend fun ready() {
        check(failed == null) { "Headhunter storage has an uncertain write; reopen and replay it before writing again" }
        if (!restored) restoreLocked()
    }

    private suspend fun restoreLocked(): Int = withContext(fileIoContext) {
        check(failed == null) { "Reopen the failed headhunter log before recovery" }
        val nextHeads = linkedMapOf<String, HeadhunterRecord>()
        val nextVersions = HashMap<String, HeadhunterRecord>()
        var observed = 0L
        val last = log.replay { seq, bytes ->
            check(seq > observed) { "Non-monotonic headhunter ledger" }
            val ref = JsonSupport.parseMap(bytes.decodeToString())
            val id = required(ref, "id")
            val cid = ContentId(required(ref, "cid"))
            val body = JsonSupport.parseMap(checkNotNull(cas.get(cid)) { "Missing headhunter version ${cid.value}" }.decodeToString())
            check(ID.matches(id) && body["id"] == id && body["kind"] == id.substringBefore('/') && body["kind"] in KINDS) {
                "Headhunter ledger identity mismatch"
            }
            check(body["previousCid"] == nextHeads[id]?.get("cid")) { "Broken headhunter version chain for $id" }
            check(body["fields"] is Map<*, *> && body["updatedAtMs"] is Number) { "Invalid headhunter version for $id" }
            val record = body + ("cid" to cid.value)
            nextHeads[id] = record
            nextVersions[cid.value] = record
            observed = seq
        }
        check(last == observed) { "Headhunter replay sequence mismatch" }
        for (id in heads.keys) if (id !in nextHeads) board.remove("headhunter/$id")
        heads.clear(); heads.putAll(nextHeads)
        versions.clear(); versions.putAll(nextVersions)
        sequence = last
        restored = true
        for (record in heads.values) publish(record)
        heads.size
    }

    private fun validate(kind: String, id: String, fields: HeadhunterRecord, old: HeadhunterRecord?) {
        rejectSecrets(fields)
        for ((field, target) in RELATIONS) fields[field]?.let { value ->
            require(value is String) { "$field must be a record id" }
            if (value.isNotBlank()) requireRecord(value, target)
        }
        for (field in setOf("url", "origin", "sourceUrl")) (fields[field] as? String)?.let(::publicLocation)
        for (field in setOf("originalCid", "receiptCid")) (fields[field] as? String)?.let {
            require(cas.get(ContentId(it)) != null) { "Missing $field content" }
        }
        when (kind) {
            "evidence" -> {
                required(fields, "title"); required(fields, "text")
                require(fields["category"] in EVIDENCE) { "Unknown evidence category" }
            }
            "source" -> {
                required(fields, "title"); required(fields, "origin")
                require(fields["extraction"] == null || fields["extraction"] is Map<*, *>) { "Source extraction must be an object" }
            }
            "employer" -> require(anyText(fields, "name", "title", "domain")) { "Employer requires a name or domain" }
            "listing" -> { required(fields, "title"); required(fields, "text") }
            "contact" -> require(anyText(fields, "name", "title", "email", "phone")) { "Contact requires a name, email, or phone" }
            "representation" -> {
                require(anyText(fields, "employerId", "listingId")) { "Representation requires an employer or listing" }
                require(fields["status"] == null || fields["status"] in setOf("active", "withdrawn", "ended", "released", "expired")) { "Unknown representation status" }
            }
            "application" -> {
                requireRecord(required(fields, "listingId"), "listing")
                require(normalize(fields["status"] as? String) !in DELIVERED && normalize(fields["stage"] as? String) !in DELIVERED) {
                    "Preparation does not establish external delivery; record a reported action and its evidence"
                }
                fields["evidenceIds"]?.let { ids ->
                    require(ids is Iterable<*>) { "evidenceIds must be an array" }
                    for (evidenceId in ids) requireRecord(requireNotNull(evidenceId as? String) { "Evidence requires a record id" }, "evidence")
                }
            }
            "artifact" -> {
                requireRecord(required(fields, "applicationId"), "application")
                required(fields, "title"); required(fields, "content")
                require(fields["type"] in setOf("resume", "email", "phone", "qa")) { "Unknown artifact type" }
                require(fields["format"] in setOf("markdown", "text", "html")) { "Unknown artifact format" }
                require(fields["generation"] in setOf("assembled", "model")) { "Artifact requires its generation method" }
                if (fields["generation"] == "model") required(fields, "model")
                val sources = references(fields)
                require(sources.size > 0) { "Artifact requires exact source versions" }
                for (ref in sources.view) requireVersion(ref)
                require(fields["reviewStatus"] == null || fields["reviewStatus"] in setOf("proposed", "reviewed")) { "Unknown review status" }
                if (fields["reviewStatus"] == "reviewed") {
                    val review = fieldsOf(requireRecord(required(fields, "reviewId"), "review"))
                    require(old != null && review["subjectId"] == id && review["subjectCid"] == old["cid"] && review["decision"] == "approved") {
                        "Artifact review does not approve this version"
                    }
                    val previous = fieldsOf(old)
                    require((fields - REVIEW_FIELDS) == (previous - REVIEW_FIELDS)) { "Edited artifacts require a new review" }
                }
            }
            "profile" -> {
                required(fields, "title"); required(fields, "receiptCid"); required(fields, "model")
                val sources = references(fields)
                require(sources.size > 0) { "Profile requires exact source versions" }
                for (ref in sources.view) requireVersion(ref)
                require(fields["claims"] is Iterable<*>) { "Profile claims must be an array" }
                require(fields["reviewStatus"] == null || fields["reviewStatus"] == "proposed") {
                    "Profile approval requires a review of its exact version"
                }
            }
            "review" -> {
                val subject = requireVersion(required(fields, "subjectId") j required(fields, "subjectCid"))
                require(heads[subject["id"] as String]?.get("cid") == subject["cid"]) { "Stale subjectCid; review the current version" }
                require(fields["decision"] in setOf("approved", "changes-requested", "rejected")) { "Unknown review decision" }
                fields["claimId"]?.let { claimId ->
                    val claim = (fieldsOf(subject)["claims"] as? Iterable<*>)?.firstOrNull { (it as? Map<*, *>)?.get("id") == claimId } as? Map<*, *>
                    require(claimId is String && claimId.isNotBlank() && subject["kind"] == "profile" && claim != null) {
                        "Review claimId must belong to the referenced profile version"
                    }
                    if (fields["decision"] == "approved") {
                        require(claim["grounded"] == true) { "Claim approval requires grounded source evidence" }
                        for (ref in references(fieldsOf(subject)).view) require(heads[ref.a]?.get("cid") == ref.b) {
                            "Profile sources changed; curate their current versions before approval"
                        }
                    }
                }
            }
            "action" -> {
                val application = requireVersion(required(fields, "applicationId") j required(fields, "applicationCid"))
                require(application["kind"] == "application") { "Action requires an application version" }
                val listing = requireVersion(required(fields, "listingId") j required(fields, "listingCid"))
                require(listing["kind"] == "listing" && listing["id"] == fieldsOf(application)["listingId"]) { "Action listing belongs to another application" }
                (fields["contactId"] as? String)?.takeIf { it.isNotBlank() }?.let {
                    require(requireVersion(it j required(fields, "contactCid"))["kind"] == "contact") { "Action requires a contact version" }
                }
                required(fields, "type")
                require(fields["status"] in setOf("prepared", "reported")) {
                    "Actions edited here are prepared or user-reported; they are not confirmed delivery receipts"
                }
                if (fields["status"] == "reported") {
                    require(anyText(fields, "receiptCid", "reference", "notes")) { "A reported action requires its evidence or account" }
                }
                (fields["artifactId"] as? String)?.let { artifactId ->
                    val artifact = requireVersion(artifactId j required(fields, "artifactCid"))
                    require(artifact["kind"] == "artifact" && fieldsOf(artifact)["applicationId"] == fields["applicationId"]) {
                        "Action artifact belongs to another application"
                    }
                }
            }
        }
    }

    private fun requireRecord(id: String, kind: String): HeadhunterRecord =
        requireNotNull(heads[id]?.takeIf { it["kind"] == kind }) { "Unknown $kind record: $id" }

    /** Recording later edits must not move an earlier action to a new listing or mailbox. */
    private fun actionFields(id: String?, fields: HeadhunterRecord): HeadhunterRecord {
        val previous = id?.let { heads[it] }?.let(::fieldsOf).orEmpty()
        val applicationId = fields["applicationId"] as? String ?: return fields
        val applicationCid = fields["applicationCid"] ?: previous["applicationCid"]?.takeIf { previous["applicationId"] == applicationId }
            ?: heads[applicationId]?.get("cid")
        val owner = (applicationCid as? String)?.let { versions[it] }?.let(::fieldsOf).orEmpty()
        val listingId = fields["listingId"] ?: owner["listingId"]
        val listingCid = fields["listingCid"] ?: previous["listingCid"]?.takeIf { previous["listingId"] == listingId }
            ?: (listingId as? String)?.let { heads[it]?.get("cid") }
        val contactId = fields["contactId"] ?: owner["contactId"]
        val contactCid = fields["contactCid"] ?: previous["contactCid"]?.takeIf { previous["contactId"] == contactId }
            ?: (contactId as? String)?.let { heads[it]?.get("cid") }
        return fields + mapOf("applicationCid" to applicationCid, "listingId" to listingId, "listingCid" to listingCid) +
            if (contactId == null) emptyMap() else mapOf("contactId" to contactId, "contactCid" to contactCid)
    }

    private fun requireVersion(ref: HeadhunterReference): HeadhunterRecord =
        requireNotNull(versions[ref.b]?.takeIf { it["id"] == ref.a }) { "Unknown source version: ${ref.a} at ${ref.b}" }

    private fun publish(record: HeadhunterRecord) {
        board.putIf("headhunter/${record["id"]}", record, "headhunter") { it != record }
    }

    companion object {
        val KINDS = setOf("evidence", "source", "employer", "listing", "contact", "representation", "application", "artifact", "profile", "review", "action")
        val EVIDENCE = setOf("resume", "experience", "project", "commentary", "correction")
        val DELIVERED = setOf("sent", "submitted", "applied", "contacted", "confirmed", "delivered", "completed")
        val REVIEW_FIELDS = setOf("reviewStatus", "reviewId")
        val RELATIONS = mapOf("employerId" to "employer", "listingId" to "listing", "sourceId" to "source", "contactId" to "contact", "applicationId" to "application", "artifactId" to "artifact")
        val ID = Regex("[a-z]+/[A-Za-z0-9._:-]+")
        val SECRET_KEYS = setOf("cookie", "cookies", "setcookie", "authorization", "proxyauthorization", "password", "passwd", "secret", "clientsecret", "accesstoken", "refreshtoken", "apikey", "token", "credentials", "storageState".lowercase())
        val SPACE = Regex("\\s+")

        fun normalize(value: String?): String = SPACE.replace(value.orEmpty().trim().lowercase(), " ")

        fun required(fields: HeadhunterRecord, key: String): String =
            requireNotNull((fields[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }) { "Record requires $key" }

        @Suppress("UNCHECKED_CAST")
        fun fieldsOf(record: HeadhunterRecord): HeadhunterRecord = record["fields"] as HeadhunterRecord

        fun reference(record: HeadhunterRecord): HeadhunterRecord = mapOf("id" to record["id"], "cid" to record["cid"])

        fun references(fields: HeadhunterRecord): Series<HeadhunterReference> {
            val values = requireNotNull(fields["sources"] as? Iterable<*>) { "sources must be an array of id/cid references" }
            return SeriesBuffer<HeadhunterReference>().apply {
                for (value in values) {
                    require(value is Map<*, *>) { "Source reference must be an object" }
                    val id = requireNotNull((value["id"] as? String)?.takeIf { it.isNotBlank() }) { "Source reference requires id" }
                    val cid = requireNotNull((value["cid"] as? String)?.takeIf { it.isNotBlank() }) { "Source reference requires cid" }
                    add(id j cid)
                }
            }.drain()
        }

        private fun anyText(fields: HeadhunterRecord, vararg keys: String): Boolean =
            keys.any { (fields[it] as? String)?.isNotBlank() == true }

        private fun rejectSecrets(value: Any?) {
            when (value) {
                is Map<*, *> -> for ((key, item) in value) {
                    val normalized = key.toString().lowercase().filter { it.isLetterOrDigit() }
                    require(normalized !in SECRET_KEYS) { "Keep credentials outside records; use credentialRef" }
                    if (normalized == "name" && item is String)
                        require(item.lowercase().filter { it.isLetterOrDigit() } !in SECRET_KEYS) { "Secret headers do not belong in records" }
                    rejectSecrets(item)
                }
                is Iterable<*> -> for (item in value) rejectSecrets(item)
            }
        }

        private fun publicLocation(location: String) {
            if (!location.contains("://")) return
            require(!location.substringAfter("://").substringBefore('/').substringBefore('?').contains('@')) { "Keep URL credentials outside records" }
            for (argument in location.substringAfter('?', "").substringBefore('#').split('&')) {
                val key = argument.substringBefore('=').lowercase().filter { it.isLetterOrDigit() }
                require(key !in SECRET_KEYS && key !in setOf("signature", "xsig", "xamzsignature")) { "Keep signed or credential-bearing URLs outside records" }
            }
        }

        /** Canonicalization is the CAS/JSON boundary; executable and opaque values are rejected. */
        fun canonical(value: Any?): String = when (value) {
            is Map<*, *> -> value.entries.sortedBy { require(it.key is String) { "Record keys must be strings" }; it.key as String }
                .joinToString(prefix = "{", postfix = "}") { JsonSupport.stringify(it.key) + ":" + canonical(it.value) }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
            is Join<*, *> -> {
                require(value.a is Int && value.b is Function1<*, *>) { "Record values must be JSON data" }
                @Suppress("UNCHECKED_CAST")
                canonical((value as Series<Any?>).view)
            }
            null, is String, is Boolean -> JsonSupport.stringify(value)
            is Number -> {
                val number = value.toDouble()
                require(number.isFinite()) { "Record numbers must be finite" }
                require(value !is Long || value in -9_007_199_254_740_991L..9_007_199_254_740_991L) { "Integer exceeds Confix's exact JSON number range" }
                if (number in -9_007_199_254_740_991.0..9_007_199_254_740_991.0 && number % 1.0 == 0.0)
                    number.toLong().toString() else JsonSupport.stringify(value)
            }
            else -> throw IllegalArgumentException("Record values must be JSON data")
        }
    }
}
