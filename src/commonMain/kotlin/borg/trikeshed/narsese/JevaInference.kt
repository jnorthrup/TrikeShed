/*
 * Kotlin commonMain adaptation of Jeva e2104e0720100f979f2cdd456a1cbbee96da0abd.
 * Copyright 2026 Qi Li and Xinchao Wang. Upstream licensed under Apache-2.0.
 * Modified for Join/Series tensor oracles and bounded mapped inference.
 * See NOTICE.md and src/commonMain/resources/licenses/apache-2.0.txt.
 */
package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.common.Files
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.yaml.parse as yamlOf
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.MatrixOperations
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/** A cooperative computation bound: expiry is evidence failure, never owner cancellation. */
class JevaDeadline(val timeoutMs: Long, val phase: String, clock: TimeSource = TimeSource.Monotonic) {
    private val started = clock.markNow()
    var location: String = phase
    class Expired(val location: String, val timeoutMs: Long, val elapsedMs: Long) :
        IllegalStateException("Jeva $location exceeded $timeoutMs ms (elapsed $elapsedMs ms)")
    init { require(timeoutMs > 0) }
    fun check() {
        val elapsed = started.elapsedNow()
        if (elapsed >= timeoutMs.milliseconds) throw Expired(location, timeoutMs, elapsed.inWholeMilliseconds)
    }
}

/** One serial inference owner: mapped checkpoint, causal text backbone and factorized decision memory. */
class JevaInference(
    val weights: JevaTensors,
    val tokenizer: JevaTokenizer,
    val backbone: JevaEncoder,
    val model: JevaModel,
    val config: JevaConfig,
    val identity: String,
) {
    private var closed = false
    // Key tokens are included in the retained payload accounting; Series closure identity is not a cache key.
    val options = JevaCache<List<Int>, Join<Int, JevaOptionMemory>>(4096, 128L * 1024 * 1024) { it.a * 4L + it.b.nbytes }

    fun infer(state: String, questions: JevaQuestions): Map<String, Any?> {
        check(!closed) { "Jeva inference is closed" }
        val answers = LinkedHashMap<String, Any?>()
        var requested = 0L; var encodedState = 0L; var encodedOptions = 0L; var cachedOptions = 0L
        for ((id, question) in questions) {
            val deadline = JevaDeadline(config.timeoutMs, "decision '$id'")
            val check = deadline::check
            deadline.location = "${deadline.phase} tokenization"
            val stateTokens = tokenizer.encode("Decision type: ${question.type}\nQuestion: ${question.instructions}\nState:\n$state", config.maxStateLength, check)
            require(stateTokens.size > 0)
            val encoder = tokenizer
            val optionLimit = config.maxOptionLength
            val candidates = question.options α { option ->
                encoder.encode("Option for ${question.type}: $option", optionLimit, check)
            }
            // Finish token-limit validation before any model execution. No hidden truncation or option removal.
            val tokens = candidates.view.map { it.toList() }
            requested += stateTokens.size + tokens.sumOf { it.size.toLong() }
            encodedState += stateTokens.size
            val context = model.context(backbone.encode(stateTokens, check) { layer, token ->
                deadline.location = "${deadline.phase} state layer $layer token $token"
            }, check = check)
            val resolved = HashMap<List<Int>, JevaOptionMemory>()
            val memories = ArrayList<JevaOptionMemory>()
            for (key in tokens) {
                check()
                val known = resolved[key] ?: options[key]?.b
                val memory = if (known != null) {
                    cachedOptions += key.size; known
                } else {
                    encodedOptions += key.size
                    val option = memories.size
                    val projected = model.options(backbone.encode(key.toSeries(), check) { layer, token ->
                        deadline.location = "${deadline.phase} option $option layer $layer token $token"
                    }, check = check)
                    deadline.location = "${deadline.phase} option $option projection"
                    model.decision.compile(projected, check)[0].also { options.put(key, key.size j it) }
                }
                resolved[key] = memory; memories.add(memory)
            }
            deadline.location = "${deadline.phase} decision head"
            val output = model.decision.forward(context, model.decision.assemble(memories.toSeries(), check), check = check)
            val q = JevaMath.array(output.q).map(Float::toDouble)
            val pNull = if (question.nullLabel == null) 0.0 else output.pNull.b(shapeOf(0)).toDouble()
            answers[id] = mapOf("kind" to question.type, "candidate_probabilities" to q,
                "null_probability" to pNull, "noul" to if (question.type == "noul") q[1] else null)
            check()
        }
        return Jeva.answers(mapOf("model" to identity, "answers" to answers, "usage" to mapOf(
            "input_tokens" to encodedState + encodedOptions, "requested_input_tokens" to requested,
            "encoded_state_tokens" to encodedState, "encoded_option_tokens" to encodedOptions,
            "cached_option_tokens" to cachedOptions, "truncated_tokens" to 0L, "output_tokens" to 0L)), questions)
    }

    fun close() {
        if (!closed) { closed = true; options.clear() }
        var failure: Throwable? = null
        try { model.decision.matrix.close() } catch (e: Throwable) { failure = e }
        try { weights.close() } catch (e: Throwable) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
        failure?.let { throw it }
    }

    /** Releases the head and the device session together. */
    private class DeviceTensors(val head: JevaHeadTensors, val session: EncoderSession) : JevaTensors by head {
        override fun close() { try { head.close() } finally { session.close() } }
    }

    companion object {
        const val VERSION = "kotlin-common-f32-2"
        const val DEVICE_VERSION = "device-f32-1"

        /** The device lane: backbone from [encoders], head from `head.pt`, tokenizer beside the device weights, no Python. */
        fun loadDevice(config: JevaConfig, encoders: EncoderOperations): JevaInference {
            val directory = config.model
            val configuration = Files.readString("$directory/config.yaml")
            val settings = requireNotNull(yamlOf(configuration)["model"] as? Map<*, *>) { "Jeva config requires model mapping" }
            val tokenizerText = Files.readString("$directory/backbone_mlx/tokenizer.json")
            val headBytes = Files.readAllBytes("$directory/head.pt")
            val session = encoders.open("$directory/backbone_mlx")
            try {
                val tokenizer = JevaTokenizer.parse(tokenizerText)
                val head = JevaHeadTensors(JevaHead.parse(headBytes))
                val decision = JevaDecision(JevaDecisionConfig.from(settings), head::get, JevaMatrix(session.matrix))
                val model = JevaModel(session.hiddenSize, JevaPooling.of(settings["pooling"] as? String ?: "last"), decision, head::get)
                val identity = config.model + "@" + DEVICE_VERSION + ":" + ContentId.of(jsonOf(mapOf(
                    "runtime" to session.identity, "config" to ContentId.of(configuration.encodeToByteArray()).hex,
                    "head" to ContentId.of(headBytes).hex, "tokenizer" to ContentId.of(tokenizerText.encodeToByteArray()).hex,
                    "state_limit" to config.maxStateLength, "option_limit" to config.maxOptionLength,
                )).encodeToByteArray()).hex
                return JevaInference(DeviceTensors(head, session), tokenizer, session, model, config, identity)
            } catch (failure: Throwable) {
                try { session.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun load(config: JevaConfig, files: FileOperations, matrices: MatrixOperations? = null,
                 channels: (String) -> FileChannel): JevaInference {
            val deadline = JevaDeadline(config.timeoutMs, "load")
            val check = deadline::check
            val weights = JevaWeights.open(files, config.model, channels, check = check)
            var matrix: JevaMatrix? = null
            try {
                val arithmetic = JevaMatrix(matrices?.open()).also { matrix = it }
                deadline.location = "load configuration"
                val yaml = yamlOf(weights.text("config.yaml", 1024 * 1024, check))
                val settings = requireNotNull(yaml["model"] as? Map<*, *>) { "Jeva config requires model mapping" }
                val backboneConfig = JevaBackboneConfig.parse(weights.text("backbone_full/config.json", 1024 * 1024, check))
                deadline.location = "load tokenizer"
                val tokenizer = JevaTokenizer.parse(weights.text("tokenizer/tokenizer.json", 64 * 1024 * 1024, check), check)
                deadline.location = "load decision head"
                check()
                val decision = JevaDecision(JevaDecisionConfig.from(settings), weights::get, arithmetic)
                val model = JevaModel(backboneConfig.hiddenSize, JevaPooling.of(settings["pooling"] as? String ?: "last"), decision, weights::get)
                deadline.location = "load checkpoint identity"
                val identity = config.model + "@" + VERSION + ":" + ContentId.of(jsonOf(mapOf(
                    "weights" to weights.contentIdentity(check), "state_limit" to config.maxStateLength,
                    "option_limit" to config.maxOptionLength, "arithmetic" to "float32",
                    "matrix" to arithmetic.identity,
                )).encodeToByteArray()).hex
                check()
                return JevaInference(weights, tokenizer, JevaBackbone(backboneConfig, weights::get, matrix = arithmetic), model, config, identity)
            } catch (failure: Throwable) {
                try { matrix?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { weights.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }
}
