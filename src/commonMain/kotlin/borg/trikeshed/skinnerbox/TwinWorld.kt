package borg.trikeshed.skinnerbox

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

data class TwinFit(val contextTokens: Long, val slots: Long, val requiredBytes: Long)
data class TwinHardwareProjection(val gpuType: String, val labelGb: Long, val usableBytes: Long, val measuredRevisions: Int) {
    val measured: Boolean get() = measuredRevisions > 0
}

/** Immutable derived result. It is never admitted as an authoritative observation or an execution qualification. */
class TwinProjection internal constructor(val savepoint: ContentId, val hardware: TwinHardwareProjection?,
    val fit: TwinFit?, fields: Map<String, Any?>) {
    private val wire = MeshPersistenceCodec.encode(fields).also { require(it.size <= 64 * 1024) { "Twin projection reply bound" } }
    fun fields(): Map<String, Any?> = MeshPersistenceCodec.decode(wire, 64 * 1024)
}

/** Pure interpretation of one model/hardware/continent coordinate, using the original MTP unified-KV formula. */
object TwinWorld {
    private const val GIB = 1L shl 30
    private const val MAX_MEMORY = 1L shl 40
    private const val MAX_CONTEXT = 1_048_576L
    private data class Memory(val layers: Long, val heads: Long, val k: Long, val v: Long, val recurrent: Long, val mtp: Long)
    private data class Family(val context: Long, val allowance: Long, val memory: Memory)
    private data class Quant(val observation: TwinObservation, val name: String, val sha: String, val bytes: Long,
        val source: Map<String, String>?)
    private data class Rental(val observation: TwinObservation, val country: String, val price: Long)

    fun project(store: TwinStore, model: String, hardware: String, continent: String): TwinProjection {
        TwinKey("model", model); TwinKey("hardware", "class/$hardware"); TwinKey("region", "continent/$continent")
        val savepoint = store.savepoint()
        val inputs = linkedMapOf<ContentId, TwinObservation>()
        val problems = mutableListOf<String>()
        var card: TwinHardwareProjection? = null
        var fitted: TwinFit? = null
        var line: Map<String, Any?>? = null
        fun keep(observation: TwinObservation): TwinObservation { inputs[observation.id] = observation; return observation }
        fun lookup(kind: String, key: String): TwinObservation? = store.get(TwinKey(kind, key))?.let(::keep)
        fun problem(observation: TwinObservation?, message: String) {
            require(problems.size < 192) { "Twin projection diagnostic bound" }
            problems.add(if (observation == null) message else "${observation.key.kind}/${observation.key.key}: $message")
        }
        fun finish(): TwinProjection = TwinProjection(savepoint, card, fitted, mapOf(
            "savepoint" to savepoint.value, "model" to model, "hardware" to hardware, "continent" to continent,
            "capacity" to card?.let { mapOf("gpu_type_id" to it.gpuType, "label_gb" to it.labelGb,
                "usable_bytes" to it.usableBytes, "measured_revisions" to it.measuredRevisions.toLong(),
                "basis" to if (it.measured) "measured" else "planned-15/16-label") },
            "line" to line, "problems" to problems.toList(),
            "provenance" to inputs.values.sortedBy { it.id.value }.map { mapOf("contentId" to it.id.value, "source" to it.source.wire) },
        ))
        val modelObservation = lookup("model", model)
        val classObservation = lookup("hardware", "class/$hardware")
        val region = lookup("region", "continent/$continent")
        if (modelObservation == null) problem(null, "model/$model: missing twin")
        if (classObservation == null) problem(null, "hardware/class/$hardware: missing twin")
        if (region == null) problem(null, "region/continent/$continent: missing twin")
        if (modelObservation == null || classObservation == null || region == null) return finish()
        val familyObservation = try { lookup("model", "family/" + text(modelObservation.fields(), "family")) }
        catch (failure: IllegalArgumentException) { problem(modelObservation, failure.message ?: "invalid family"); null }
        if (familyObservation == null) { problem(modelObservation, "family twin is absent or malformed"); return finish() }
        val family = try { family(familyObservation.fields()) }
        catch (failure: IllegalArgumentException) { problem(familyObservation, failure.message ?: "invalid family"); return finish() }
        val classFields = classObservation.fields()
        val label: Long
        val gpuType: String
        try {
            label = integer(classFields, "label_gb", 1, 1024)
            gpuType = text(classFields, "gpu_type_id")
        } catch (failure: IllegalArgumentException) { problem(classObservation, failure.message ?: "invalid hardware class"); return finish() }
        val revisions = mutableListOf<Long>()
        for (observation in store.select("hardware") { it.key.startsWith("revision/$hardware/") }.view) {
            keep(observation)
            if (observation.source != TwinSource.MEASURED) { problem(observation, "revision is not measured"); continue }
            try { revisions.add(integer(observation.fields(), "free_bytes", 0, MAX_MEMORY)) }
            catch (failure: IllegalArgumentException) { problem(observation, failure.message ?: "invalid revision"); }
        }
        val budget = revisions.minOrNull() ?: multiply(label, GIB / 16 * 15)
        card = TwinHardwareProjection(gpuType, label, budget, revisions.size)
        val quants = mutableListOf<Quant>()
        for (observation in store.select("quant") { it.key.startsWith("$model/") }.view) {
            keep(observation)
            try {
                val fields = observation.fields()
                if (text(fields, "variant") != "MTP") continue
                quants.add(quant(observation, fields, model))
            } catch (failure: IllegalArgumentException) { problem(observation, failure.message ?: "invalid quant") }
        }
        val conflictingArtifacts = quants.groupBy { it.sha }.filterValues { it.size > 1 }.keys
        for (candidate in quants) if (candidate.sha in conflictingArtifacts)
            problem(candidate.observation, "artifact SHA256 has conflicting quant descriptors")
        var selected: Quant? = null
        for (candidate in quants.filter { it.sha !in conflictingArtifacts }.sortedWith(compareByDescending<Quant> { it.bytes }.thenBy { it.name })) {
            try {
                val attempt = fit(family, candidate.bytes, budget)
                if (attempt != null) { selected = candidate; fitted = attempt; break }
            } catch (failure: IllegalArgumentException) { problem(candidate.observation, failure.message ?: "fit overflow") }
        }
        val rentals = mutableListOf<Rental>()
        for (observation in store.select("rental") { it.key.split('/', limit = 3).let { parts -> parts.size == 3 && parts[1] == hardware } }.view) {
            keep(observation)
            try {
                require(observation.key.key.split('/', limit = 3).all { it.isNotEmpty() }) { "rental coordinate is incomplete" }
                val fields = observation.fields()
                val country = text(fields, "country_code")
                val countryObservation = lookup("region", "country/$country")
                if (countryObservation == null) { problem(observation, "country/$country is absent"); continue }
                if (text(countryObservation.fields(), "continent") != continent) continue
                rentals.add(Rental(observation, country, integer(fields, "micro_usd_per_hour", 0, Long.MAX_VALUE)))
            } catch (failure: IllegalArgumentException) { problem(observation, failure.message ?: "invalid rental") }
        }
        val rental = rentals.minWithOrNull(compareBy<Rental> { it.price }.thenBy { it.observation.key.key })
        if (selected == null || fitted == null) problem(null, "model/$model on hardware/$hardware: no MTP quant fits")
        if (rental == null) problem(null, "hardware/$hardware in region/$continent: no valid rental")
        if (selected != null && fitted != null && rental != null) {
            try {
                val fit = fitted!!
                line = mapOf("quant" to selected.name, "sha256" to selected.sha, "bytes" to selected.bytes,
                    "artifact_source" to selected.source, "context_tokens" to fit.contextTokens, "slots" to fit.slots,
                    "budget_bytes" to budget, "required_bytes" to fit.requiredBytes, "allowance_bytes" to family.allowance,
                    "headroom_ppm" to 10_000L, "draft_tokens" to 2L,
                    "request_bytes" to (multiply(fit.contextTokens, 32) + 8192).coerceIn(128 * 1024L, 8 * 1024 * 1024L),
                    "volume_gb" to ceiling(add(selected.bytes, maxOf(2 * GIB, selected.bytes / 20)), 1_000_000_000),
                    "rental" to rental.observation.key.key, "country" to rental.country,
                    "rental_micro_usd_per_hour" to rental.price,
                    "posted_micro_usd_per_hour" to MeshPrice.of(rental.price).postedMicroUsd(),
                    "price_rounding" to "half-up-once", "measured_capacity" to card!!.measured,
                    "execution_qualified" to false)
            } catch (failure: IllegalArgumentException) { problem(rental.observation, failure.message ?: "price overflow") }
        }
        return finish()
    }

    private fun family(fields: Map<String, Any?>): Family {
        val raw = fields["memory"] as? Map<*, *> ?: throw IllegalArgumentException("memory must be an object")
        @Suppress("UNCHECKED_CAST") val memory = raw as Map<String, Any?>
        return Family(integer(fields, "trained_context", 256, MAX_CONTEXT), integer(fields, "compute_allowance_bytes", 256L shl 20, MAX_MEMORY),
            Memory(integer(memory, "attention_layers", 1, 1024), integer(memory, "kv_heads", 1, 1024),
                integer(memory, "k_row_bytes", 1, 1L shl 20), integer(memory, "v_row_bytes", 1, 1L shl 20),
                integer(memory, "recurrent_bytes_per_sequence", 0, MAX_MEMORY), integer(memory, "mtp_kv_layers", 1, 8)))
    }

    private fun quant(observation: TwinObservation, fields: Map<String, Any?>, model: String): Quant {
        val name = observation.key.key.removePrefix("$model/")
        require(name.length in 1..16 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) { "quantization name" }
        val sha = text(fields, "sha256")
        require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }) { "quant SHA256" }
        val sourceKeys = setOf("repository", "revision", "filename", "xet_hash")
        val present = sourceKeys.count { it in fields && fields[it] != null }
        require(present == 0 || present == sourceKeys.size) { "incomplete quant artifact source" }
        val source = if (present == 0) null else sourceKeys.associateWith { text(fields, it) }
        return Quant(observation, name, sha, integer(fields, "bytes", 1, MAX_MEMORY), source)
    }

    private fun fit(family: Family, bytes: Long, budget: Long): TwinFit? {
        fun need(cells: Long, slots: Long): Long {
            val memory = family.memory
            val attention = multiply(multiply(multiply(cells, 256), memory.heads),
                multiply(add(memory.k, memory.v), add(memory.layers, memory.mtp)))
            val recurrent = multiply(multiply(memory.recurrent, 3), slots)
            return add(add(add(bytes, attention), recurrent), add(family.allowance, ceiling(budget, 100)))
        }
        fun largest(slots: Long): Long {
            var low = 0L; var high = family.context / 256
            while (low < high) {
                val middle = low + (high - low + 1) / 2
                if (need(middle, slots) <= budget) low = middle else high = middle - 1
            }
            return low
        }
        val four = largest(4)
        val slots = if (four * 256 >= 131_072) 4L else 1L
        val cells = if (slots == 4L) four else largest(1)
        return if (cells == 0L) null else TwinFit(cells * 256, slots, need(cells, slots))
    }

    private fun integer(fields: Map<String, Any?>, key: String, low: Long, high: Long): Long {
        val value = fields[key] as? Long ?: throw IllegalArgumentException("$key must be an exact signed 64-bit integer")
        require(value in low..high) { "$key is outside supported bounds" }
        return value
    }
    private fun text(fields: Map<String, Any?>, key: String): String {
        val value = fields[key] as? String ?: throw IllegalArgumentException("$key must be text")
        require(value.length in 1..512 && value.none { it.code < 32 }) { "$key text bound" }
        return value
    }
    private fun add(a: Long, b: Long): Long { require(a >= 0 && b >= 0 && a <= Long.MAX_VALUE - b) { "Twin arithmetic overflow" }; return a + b }
    private fun multiply(a: Long, b: Long): Long { require(a >= 0 && b >= 0 && (a == 0L || b <= Long.MAX_VALUE / a)) { "Twin arithmetic overflow" }; return a * b }
    private fun ceiling(a: Long, b: Long): Long = a / b + if (a % b == 0L) 0 else 1
}
