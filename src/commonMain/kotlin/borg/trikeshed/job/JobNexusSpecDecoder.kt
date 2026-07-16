package borg.trikeshed.job

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.value
import borg.trikeshed.parse.confix.Syntax

/**
 * JobNexusSpecDecoder — decodes a spec from JSON/YAML/CBOR bytes.
 * All syntaxes produce the same canonical spec.
 */
object JobNexusSpecDecoder {

    fun decode(syntax: Syntax, bytes: ByteArray): JobNexusSpec {
        val doc: ConfixDoc = when (syntax) {
            Syntax.CBOR -> decodeCborSpec(bytes)
            else -> confixDoc(bytes, if (syntax == Syntax.YAML) Syntax.YAML else Syntax.JSON)
        }
        return decodeFromDoc(doc)
    }

    private fun decodeFromDoc(doc: ConfixDoc): JobNexusSpec {
        val channelSpec = ChannelSpec(
            commands = doc.value("channels", "commands").toIntOrNullCoerce() ?: 64,
            committed = doc.value("channels", "committed").toIntOrNullCoerce() ?: 64,
            facts = doc.value("channels", "facts").toIntOrNullCoerce() ?: 128,
            activations = doc.value("channels", "activations").toIntOrNullCoerce() ?: 64,
            telemetry = doc.value("channels", "telemetry").toIntOrNullCoerce() ?: 32,
        )

        val backendStr = doc.value("storage", "backend")?.toString() ?: "memory"
        val durabilityStr = doc.value("storage", "durability")?.toString() ?: "none"
        val storageSpec = StorageSpec(
            backend = when (backendStr.lowercase()) {
                "file" -> StorageBackend.File
                "linuxbtrfs", "linux_btrfs", "btrfs" -> StorageBackend.LinuxBtrfs
                else -> StorageBackend.Memory
            },
            durability = when (durabilityStr.lowercase()) {
                "fsync" -> Durability.Fsync
                else -> Durability.None
            },
            checkpointEvery = doc.value("storage", "checkpointEvery").toIntOrNullCoerce() ?: 0,
        )

        val supervisionSpec = SupervisionSpec(
            drainTimeoutMs = doc.value("supervision", "drainTimeoutMs").toLongOrNullCoerce() ?: 5_000L,
        )

        val reteSpec = ReteSpec(
            cycleBudget = doc.value("rete", "cycleBudget").toIntOrNullCoerce() ?: 1_000,
        )

        return JobNexusSpec(channelSpec, storageSpec, supervisionSpec, reteSpec)
    }

    /**
     * Decode a CBOR-encoded spec. The CanonicalCbor.encode(spec) produces a
     * JSON-formatted canonical representation, so we parse it as JSON.
     */
    private fun decodeCborSpec(bytes: ByteArray): ConfixDoc {
        // Our canonical bytes are JSON; decode them as JSON
        return confixDoc(bytes, Syntax.JSON)
    }
}

/**
 * JobNexusSpecValidator — validates a spec before opening.
 */
object JobNexusSpecValidator {

    data class Result(val valid: Boolean, val errors: List<String>)

    fun validate(spec: JobNexusSpec): Result {
        val errors = mutableListOf<String>()
        if (spec.channels.commands <= 0) errors.add("channels.commands must be > 0")
        if (spec.channels.committed <= 0) errors.add("channels.committed must be > 0")
        if (spec.channels.facts <= 0) errors.add("channels.facts must be > 0")
        if (spec.channels.activations <= 0) errors.add("channels.activations must be > 0")
        if (spec.channels.telemetry <= 0) errors.add("channels.telemetry must be > 0")
        if (spec.supervision.drainTimeoutMs <= 0) errors.add("supervision.drainTimeoutMs must be > 0")
        return Result(errors.isEmpty(), errors)
    }
}

/**
 * Coerce a Confix-reified scalar to Int. Tolerates JSON numbers reified as
 * Double (e.g. 256 → 256.0), Int, Long, and string-wrapped numerics.
 * Named with the `Coerce` suffix to avoid collision with the stdlib
 * `String.toIntOrNull()` and `Any?.toIntOrNull()` overloads.
 */
internal fun Any?.toIntOrNullCoerce(): Int? = when (this) {
    null -> null
    is Int -> this
    is Long -> this.toInt()
    is Number -> this.toInt()
    is String -> this.toIntOrNull()  // explicit stdlib String.toIntOrNull
    else -> {
        val s = this.toString().trim()
        if (s.contains('.')) s.toDoubleOrNull()?.toInt() else s.toLongOrNull()?.toInt()
    }
}

/**
 * Coerce a Confix-reified scalar to Long. Tolerates JSON numbers reified as
 * Double, Int, Long, and string-wrapped numerics.
 */
internal fun Any?.toLongOrNullCoerce(): Long? = when (this) {
    null -> null
    is Long -> this
    is Int -> this.toLong()
    is Number -> this.toLong()
    is String -> this.toLongOrNull()  // explicit stdlib String.toLongOrNull
    else -> {
        val s = this.toString().trim()
        if (s.contains('.')) s.toDoubleOrNull()?.toLong() else s.toLongOrNull()
    }
}
