package borg.trikeshed.userspace.nio.platform.spi

import borg.trikeshed.common.Files
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.JevaBackboneConfig
import kotlin.test.*

class MlxBackboneAdmissionTest {
    private fun checkpoint(directory: String, invalid: String = "", attentionBias: Boolean = false) {
        val config = """{"model_type":"qwen3_5_text","hidden_act":"silu","vocab_size":32,"hidden_size":32,"intermediate_size":32,"num_hidden_layers":2,"layer_types":["linear_attention","full_attention"],"num_attention_heads":1,"num_key_value_heads":1,"head_dim":32,"linear_num_key_heads":1,"linear_num_value_heads":1,"linear_key_head_dim":32,"linear_value_head_dim":32,"linear_conv_kernel_dim":4,"rms_norm_eps":0.000001,"max_position_embeddings":64,"attention_bias":$attentionBias,"rope_parameters":{"rope_type":"default","rope_theta":10000,"partial_rotary_factor":1},"quantization":{"group_size":32,"bits":4}}"""
        Files.write("$directory/config.json", config.encodeToByteArray())
        val entries = mutableListOf<String>()
        var offset = 0
        fun tensor(name: String, dtype: String, vararg dims: Int) {
            val size = dims.fold(4) { n, d -> n * d }
            entries += "\"language_model.model.$name\":{\"dtype\":\"$dtype\",\"shape\":[${dims.joinToString(",")}],\"data_offsets\":[$offset,${offset + size}]}"
            offset += size
        }
        fun matrix(name: String, rows: Int, columns: Int) {
            val actualRows = if (invalid == name) rows + 1 else rows
            tensor("$name.weight", "U32", actualRows, columns / 8)
            tensor("$name.scales", "F32", actualRows, columns / 32)
            tensor("$name.biases", "F32", actualRows, columns / 32)
        }
        matrix("embed_tokens", 32, 32)
        tensor("norm.weight", if (invalid == "norm.weight") "U32" else "F32", 32)
        for (i in 0..1) {
            val layer = "layers.$i."
            tensor(layer + "input_layernorm.weight", "F32", 32)
            tensor(layer + "post_attention_layernorm.weight", "F32", 32)
            for (projection in listOf("gate_proj", "up_proj", "down_proj")) matrix(layer + "mlp.$projection", 32, 32)
            if (i == 0) {
                val at = layer + "linear_attn."
                matrix(at + "in_proj_qkv", 96, 32)
                matrix(at + "in_proj_z", 32, 32)
                matrix(at + "out_proj", 32, 32)
                tensor(at + "in_proj_a.weight", "F32", 1, 32)
                tensor(at + "in_proj_b.weight", "F32", 1, 32)
                if (invalid == "conv") tensor(at + "conv1d.weight", "F32", 96, 1, 4)
                else tensor(at + "conv1d.weight", "F32", 96, 4, 1)
                tensor(at + "A_log", "F32", 1)
                tensor(at + "dt_bias", "F32", 1)
                tensor(at + "norm.weight", "F32", 32)
            } else {
                val at = layer + "self_attn."
                matrix(at + "q_proj", 64, 32)
                matrix(at + "k_proj", 32, 32)
                matrix(at + "v_proj", 32, 32)
                matrix(at + "o_proj", 32, 32)
                tensor(at + "q_norm.weight", "F32", 32)
                tensor(at + "k_norm.weight", "F32", 32)
            }
        }
        val header = ("{" + entries.joinToString(",") + "}").encodeToByteArray()
        val bytes = ByteArray(8 + header.size + offset)
        for (i in 0 until 8) bytes[i] = (header.size.toLong() ushr (i * 8)).toByte()
        header.copyInto(bytes, 8)
        Files.write("$directory/model.safetensors", bytes)
    }

    @Test fun checkpointShapesAreCheckedBeforeInference() {
        val directory = Files.createTempDir("mlx-backbone-admission-")
        try {
            for (invalid in listOf("", "layers.1.self_attn.q_proj", "conv", "norm.weight")) {
                checkpoint(directory, invalid)
                val session = MlxMatrixOperations().open()
                try {
                    if (invalid.isEmpty()) MlxBackbone(session, directory)
                    else assertFailsWith<IllegalArgumentException> { MlxBackbone(session, directory) }
                } finally { session.close() }
            }
            checkpoint(directory, attentionBias = true)
            val session = MlxMatrixOperations().open()
            try { assertFailsWith<IllegalArgumentException> { MlxBackbone(session, directory) } }
            finally { session.close() }
        } finally { Files.deleteRecursively(directory) }
    }

    @Test fun malformedConfigAndFloatOverflowAreRejectedBeforeLoadingWeights() {
        val directory = Files.createTempDir("mlx-config-admission-")
        try {
            checkpoint(directory)
            val path = "$directory/config.json"
            val original = Files.readString(path)
            for (invalid in _l[
                original.replace("\"attention_bias\":false", "\"attention_bias\":\"false\""),
                original.replace("\"rope_theta\":10000", "\"rope_theta\":1e300"),
                original.replace("\"rope_theta\":10000", "\"rope_theta\":1e-300"),
                original.replace("\"partial_rotary_factor\":1", "\"partial_rotary_factor\":\"bad\""),
            ]) {
                Files.write(path, invalid)
                val session = MlxMatrixOperations().open()
                try { assertFails { MlxBackbone(session, directory) } } finally { session.close() }
            }
        } finally { Files.deleteRecursively(directory) }
    }

    @Test fun configuredTokenLimitRefusesBeforeReadingOrAllocatingDeviceInput() {
        val directory = Files.createTempDir("mlx-token-limit-")
        try {
            checkpoint(directory)
            val operations = MlxMatrixOperations.configured { name ->
                if (name == "TRIKESHED_MLX_MAX_TOKENS") "2" else null
            }
            val session = operations.open()
            try {
                val model = MlxBackbone(session, directory)
                val before = session.mark()
                val oversized: Series<Int> = 3 j { error("rejected tokens must not be read") }
                val error = assertFailsWith<IllegalArgumentException> { model.encode(oversized, {}, { _, _ -> }) }
                assertTrue(error.message.orEmpty().contains("token"))
                assertEquals(before, session.mark())
                class Interrupted : RuntimeException()
                assertFailsWith<Interrupted> { model.encode(s_[0], { throw Interrupted() }, { _, _ -> }) }
                assertEquals(before, session.mark())
                var checks = 0
                var allocated = 0
                assertFailsWith<Interrupted> {
                    model.encode(s_[0, 1], { if (++checks == 5) {
                        allocated = session.mark() - before
                        throw Interrupted()
                    } }, { _, _ -> })
                }
                assertEquals(5, checks, "Cancellation reached the first evaluated recurrence chunk")
                assertTrue(allocated > 0, "Cancellation must interrupt real device work")
                assertEquals(before, session.mark(), "Canceled inference must release its device intermediates")
                val short = model.encode(s_[0, 1], {}, { _, _ -> })
                assertEquals(2, short.a[0])
                assertEquals(32, short.a[1])
                assertEquals(before, session.mark())
            } finally { session.close() }
            assertEquals(0L, MlxMatrixOperations.checkpointUsage().bytes)
        } finally { Files.deleteRecursively(directory) }
    }

    @Test fun sequenceShapesAreCheckedBeforeMaterialization() {
        mlxSequenceShapes(2, JevaBackboneConfig())
        assertFailsWith<IllegalArgumentException> { mlxSequenceShapes(233_017, JevaBackboneConfig()) }
        assertFailsWith<IllegalArgumentException> { mlxSequenceShapes(2, JevaBackboneConfig(convKernel = Int.MAX_VALUE)) }
    }
}
