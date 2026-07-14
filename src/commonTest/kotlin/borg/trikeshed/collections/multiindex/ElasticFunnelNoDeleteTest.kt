package borg.trikeshed.collections.multiindex

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * C13 RED — Elastic/Funnel indexes must expose NO delete API.
 *
 * The plan: "no persistent Elastic/Funnel segment derives probes from
 * platform hashCode() or exposes delete/retract."
 *
 * Paper bounds cover insertion-only operation. Elastic/Funnel are
 * immutable segment builders — once built, no mutation.
 */
class ElasticFunnelNoDeleteTest {

    @Test
    fun elasticHashIndexHasNoDeleteMethod() {
        val idx = ElasticHashIndex.Builder<Int>(capacity = 64, seed = 1L)
            .insert((0 until 32).toList()) { it.toString() }
            .build()

        // The built index must NOT expose delete/remove/retract methods.
        val methods = idx::class.members.map { it.name }
        val forbidden = listOf("delete", "remove", "retract")
        for (name in forbidden) {
            if (methods.any { it.equals(name, ignoreCase = true) }) {
                fail("ElasticHashIndex must not expose '$name' — paper bounds are insertion-only")
            }
        }
    }

    @Test
    fun funnelHashIndexHasNoDeleteMethod() {
        val idx = FunnelHashIndex.Builder<Int>(capacity = 64, seed = 1L)
            .insert((0 until 32).toList()) { it.toString() }
            .build()

        val methods = idx::class.members.map { it.name }
        val forbidden = listOf("delete", "remove", "retract")
        for (name in forbidden) {
            if (methods.any { it.equals(name, ignoreCase = true) }) {
                fail("FunnelHashIndex must not expose '$name' — paper bounds are insertion-only")
            }
        }
    }

    @Test
    fun elasticBuilderIsSingleUse() {
        // The builder is consumed by build() — calling build() twice must fail
        val builder = ElasticHashIndex.Builder<Int>(capacity = 64, seed = 1L)
            .insert((0 until 32).toList()) { it.toString() }

        builder.build() // first build OK

        try {
            builder.build() // second build must fail
            fail("ElasticHashIndex.Builder must be single-use")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already") || e.message!!.contains("consumed"))
        }
    }
}
