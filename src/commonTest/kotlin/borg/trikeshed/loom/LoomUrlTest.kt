package borg.trikeshed.loom

import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * url 2.5.8 `Url::join` against bases whose scheme is not special ([UrlJoinVectors], printed by a scratch oracle crate
 * on the url that cocaine-rats e4898291 locks): each base parses to the oracle's fields, and each input joins to the
 * oracle's URL or is refused where it refuses.
 */
class LoomUrlTest {
    /** The oracle's rendering of [url]: its fields joined by tabs, an absent one as U+0001; "ERR" for a refusal. */
    fun fields(url: Url?): String = url?.run {
        listOf(serialization, scheme, username, password ?: "\u0001", host ?: "\u0001", port?.toString() ?: "\u0001", path, query ?: "\u0001", fragment ?: "\u0001")
            .joinToString("\t")
    } ?: "ERR"

    @Test
    fun joinAgainstBasesThatAreNotSpecial() {
        val bases = UrlJoinVectors.bases
        val inputs = UrlJoinVectors.inputs
        val misses = mutableListOf<String>()
        for (b in 0 until bases.size) {
            val base = Url.parse(bases[b])
            if (fields(base) != UrlJoinVectors.parsed[b]) misses += "parse ${bases[b]}: ${fields(base)} != ${UrlJoinVectors.parsed[b]}"
            for (i in 0 until inputs.size) {
                val joined = fields(base?.join(inputs[i]))
                val rust = UrlJoinVectors.joins[b * inputs.size + i]
                if (joined != rust) misses += "${bases[b]} join ${inputs[i]}: $joined != $rust"
            }
        }
        assertEquals(emptyList(), misses, "${misses.size} of ${bases.size * (inputs.size + 1)}")
    }
}
