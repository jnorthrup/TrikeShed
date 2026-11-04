package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.*
import kotlin.random.Random

class JevaWorkerProtocolTest {
    private class Loaded(val config: JevaConfig) : RuntimeException()

    private fun request(field: String, value: Any?): String {
        val config: MutableMap<String, Any?> = _m[
            "model" j "fixture", "device" j "mlx", "max_state_length" j 128,
            "max_option_length" j 32, "timeout_ms" j 1_000,
        ].toMutableMap()
        config[field] = value
        return jsonOf(_m["op" j "init", "config" j config])
    }

    @Test fun invalidNumericLimitsNeverReachTheLoader() = runBlocking {
        for ((field, value) in _l[
            "max_state_length" j 1.5, "max_option_length" j 4_294_967_297L,
            "timeout_ms" j 1.5, "mlx_max_tokens" j "64", "mlx_max_tokens" j 4_294_967_297L,
            "mlx_checkpoint_limit_bytes" j 0.5, "mlx_checkpoint_limit_bytes" j true,
            "mlx_max_footprint_bytes" j 9_007_199_254_740_992L,
        ]) {
            val worker = JevaWorker { throw Loaded(it) }
            assertFailsWith<IllegalArgumentException>("$field=$value must be rejected before model loading") {
                worker.exchange(request(field, value))
            }
        }
    }

    @Test fun roundedOrOverflowingNumberLiteralsNeverReachTheLoader() = runBlocking {
        val initial = request("max_state_length", 128)
        for (literal in _l["1.0000000000000001", "1e-99999999999999999999", "9223372036854775808"]) {
            val input = initial.replace("\"max_state_length\":128", "\"max_state_length\":$literal")
            assertNotEquals(initial, input)
            assertFailsWith<IllegalArgumentException>("$literal must not be rounded or narrowed") {
                JevaWorker { throw Loaded(it) }.exchange(input)
            }
        }
    }

    @Test fun exactIntegralDecimalAndExponentFormsRemainCompatible() = runBlocking {
        val initial = request("max_state_length", 128)
        for ((literal, expected) in _l["1.0" j 1, "1e2" j 100, "1000e-2" j 10, "0.0001e5" j 10]) {
            val input = initial.replace("\"max_state_length\":128", "\"max_state_length\":$literal")
            assertNotEquals(initial, input)
            val config = assertFailsWith<Loaded> { JevaWorker { throw Loaded(it) }.exchange(input) }.config
            assertEquals(expected, config.maxStateLength)
        }
        val checkpoint = request("mlx_checkpoint_limit_bytes", Long.MAX_VALUE)
            .replace("\"mlx_checkpoint_limit_bytes\":${Long.MAX_VALUE}", "\"mlx_checkpoint_limit_bytes\":92233720368547758070e-1")
        val config = assertFailsWith<Loaded> { JevaWorker { throw Loaded(it) }.exchange(checkpoint) }.config
        assertEquals(Long.MAX_VALUE, config.mlxCheckpointLimitBytes)
    }

    @Test fun validNumericLimitsReachTheLoaderUnchanged() = runBlocking {
        val worker = JevaWorker { throw Loaded(it) }
        val config = assertFailsWith<Loaded> { worker.exchange(request("mlx_checkpoint_limit_bytes", Long.MAX_VALUE)) }.config
        assertEquals(128, config.maxStateLength)
        assertEquals(32, config.maxOptionLength)
        assertEquals(1_000L, config.timeoutMs)
        assertEquals(Long.MAX_VALUE, config.mlxCheckpointLimitBytes)
    }

    @Test fun numericLimitsAgreeWithExactDecimalArithmetic() = runBlocking {
        val random = Random(20261103)
        val initial = request("mlx_checkpoint_limit_bytes", 1)
        repeat(256) {
            val sign = if (random.nextBoolean()) "-" else ""
            val literal = "$sign${random.nextInt(1, 100_000)}.${random.nextInt(0, 100_000)}e${random.nextInt(-24, 25)}"
            val expected = runCatching { BigDecimal(literal).longValueExact() }.getOrNull()?.takeIf { it >= 0 }
            val input = initial.replace("\"mlx_checkpoint_limit_bytes\":1", "\"mlx_checkpoint_limit_bytes\":$literal")
            assertNotEquals(initial, input)
            val worker = JevaWorker { throw Loaded(it) }
            if (expected == null) assertFailsWith<IllegalArgumentException>(literal) { worker.exchange(input) }
            else assertEquals(expected, assertFailsWith<Loaded>(literal) { worker.exchange(input) }.config.mlxCheckpointLimitBytes)
        }
    }

    @Test fun strictReificationRetainsDefaultNumbersAndCanExposeExactLexemes() {
        val input = """{"values":[1e2,1.0000000000000001,"1e2"]}"""
        val usual = (reifyStrict(input) as Map<*, *>)["values"] as List<*>
        assertEquals(_l[100.0, 1.0, "1e2"], usual)
        val exact = (reifyStrict(input) { "number:$it" } as Map<*, *>)["values"] as List<*>
        assertEquals(_l["number:1e2", "number:1.0000000000000001", "1e2"], exact)
    }
}
