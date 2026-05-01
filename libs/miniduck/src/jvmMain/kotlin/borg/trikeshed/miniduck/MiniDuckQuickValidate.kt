package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.*

fun main() {
    try {
        val doc = DocRowVec(listOf("id", "name"), listOf("doc1", "Alice"))
        val view = ViewRowVec(id = "doc1", key = "k1", value = 42, docLoader = { doc })
        val block = BlockRowVec.mutable()
        block.append(view)
        val sealed = block.seal()

        val text = MiniDuckBlockCodec.encode(sealed)
        println("Encoded block text: \n$text")

        val decoded = MiniDuckBlockCodec.decode(text)
        val childSeries = decoded.child ?: throw Exception("decoded block has no child series")
        if (childSeries.size != 1) throw Exception("expected 1 child, got ${childSeries.size}")

        val first = childSeries[0]
        if (first !is ViewRowVec) throw Exception("expected ViewRowVec, got ${first::class}")
        val viewDecoded = first as ViewRowVec
        println("Decoded view id=${viewDecoded.id} key=${viewDecoded.key} value=${viewDecoded.value}")

        val docSeries = viewDecoded.child ?: throw Exception("view child missing")
        val payload = docSeries[0]
        if (payload !is DocRowVec) throw Exception("expected DocRowVec payload, got ${payload::class}")
        println("Decoded doc cells: ${payload.cells}")

        println("MiniDuck quick validation succeeded")
    } catch (t: Throwable) {
        t.printStackTrace()
        kotlin.system.exitProcess(2)
    }
}
