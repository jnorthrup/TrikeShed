package borg.trikeshed.transfers

import borg.trikeshed.docs.DocumentSurface
import borg.trikeshed.graal.console.Terrain
import borg.trikeshed.landscape.*
import borg.trikeshed.lib.*
import borg.trikeshed.wiki.WikiSurface
import narchy.spacegraph.*
import narchy.spacegraph.graphics.spi.Rgba

/** A peer, mirror or web seed a transfer is fed by or feeds. */
class TransferSource(val address: String, val protocol: String, val transport: String, val state: String, val down: Double, val up: Double, val completed: Double)

/** A transfer's pieces: how many, how many are verified, and the length of one in bytes. */
class TransferPieces(val count: Int, val have: Int, val length: Double)

/** What asked for a transfer: [kind] is one of [TransferLinks.KINDS]. */
class TransferContext(val kind: String, val id: String)

/**
 * The row every transport reports and the transfers panel reads. [of] reads its JSON form, whose field names are the
 * wire's own; a field with no value is null. [`class`] is the SUMO class the content is typed by.
 */
class TransferRow(
    val id: String,
    val name: String,
    val protocol: String,
    val state: String,
    val total: Double?,
    val completed: Double,
    val down: Double,
    val up: Double,
    val pieces: TransferPieces?,
    val sources: Series<TransferSource>,
    val context: TransferContext?,
    val content: String?,
    val `class`: String?,
    val error: String?,
) {
    companion object {
        fun Map<*, *>.text(k: String): String? = this[k]?.toString()
        fun Map<*, *>.number(k: String): Double? = (this[k] as? Number)?.toDouble()

        fun of(m: Map<*, *>): TransferRow = TransferRow(
            id = m.text("id").orEmpty(),
            name = m.text("name").orEmpty(),
            protocol = m.text("protocol").orEmpty(),
            state = m.text("state").orEmpty(),
            total = m.number("total"),
            completed = m.number("completed") ?: 0.0,
            down = m.number("down") ?: 0.0,
            up = m.number("up") ?: 0.0,
            pieces = (m["pieces"] as? Map<*, *>)?.let { p -> TransferPieces(p.number("count")?.toInt() ?: 0, p.number("have")?.toInt() ?: 0, p.number("length") ?: 0.0) },
            sources = ((m["sources"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()).toSeries() α { s ->
                TransferSource(s.text("address").orEmpty(), s.text("protocol").orEmpty(), s.text("transport").orEmpty(), s.text("state").orEmpty(),
                    s.number("down") ?: 0.0, s.number("up") ?: 0.0, s.number("completed") ?: 0.0)
            },
            context = (m["context"] as? Map<*, *>)?.let { c -> TransferContext(c.text("kind").orEmpty(), c.text("id").orEmpty()) },
            content = m.text("content"),
            `class` = m.text("class"),
            error = m.text("error"),
        )
    }
}

/**
 * Everything at once: how many transfers, the bytes verified ([completed], over every row) and the sum of the totals
 * that are known ([total]; [untotalled] rows have none yet), the rates now, and how many stand in each state.
 */
class TransferRollup(
    val transfers: Int, val completed: Double, val total: Double, val untotalled: Int, val down: Double, val up: Double,
    val waiting: Int, val active: Int, val paused: Int, val complete: Int, val failed: Int,
) {
    companion object {
        fun of(rows: Series<TransferRow>): TransferRollup {
            val v = rows.view
            fun count(state: String) = v.count { it.state == state }
            return TransferRollup(rows.size, v.sumOf { it.completed }, v.sumOf { it.total ?: 0.0 }, v.count { it.total == null },
                v.sumOf { it.down }, v.sumOf { it.up }, count("waiting"), count("active"), count("paused"), count("complete"), count("error"))
        }
    }
}

/**
 * The transports whose transfers `GET /api/transfers` serves: each reporter gives its current rows in their JSON form.
 * A transport registers its reporter when it starts; with none registered the route serves an empty list.
 */
object TransferReports {
    val reporters = ArrayList<() -> Series<Map<String, Any?>>>()

    fun rows(): List<Map<String, Any?>> = reporters.flatMap { it().view }
}

/**
 * Links between the transfers panel and the pages that show what asked for a transfer, each in the form that page
 * already reads ([context]); every page links back to a transfer as [transfer]. A context's id is the page's own key:
 * a stored document `<project>/<document>`, an LCNC node what [LandscapeNavigation.node] writes after `node:` (the
 * pair `["<program>", "<node>"]`, as the board writes the node it focuses), a run its run id, a curated book its name.
 */
object TransferLinks {
    val KINDS: Series<String> = s_["run", "node", "book", "document"]

    /**
     * Where [c] is shown: a document on the documents page (`/documents#<project>/<document>`, which selects it; /graal
     * reads no document from a URL); a node on the board (`/harness#x=…&y=…&z=…&focus=node:[…]`, which keeps the focus
     * and selects no node); a run on /panels and a book on /curator, which read neither from a URL and open alone.
     */
    fun context(c: TransferContext): String? = when (c.kind) {
        "document" -> c.id.substringBefore('/', "").takeIf { it.isNotEmpty() }?.let { project ->
            "/documents#" + DocumentSurface.projectFragment(project) + "/" + WikiSurface.encode(c.id.substringAfter('/'))
        }
        "node" -> "/harness" + LandscapeNavigation.encode(LandscapeCamera(0.0, 0.0, 1.0), "node:" + c.id)
        "run" -> "/panels"
        "book" -> "/curator"
        else -> null
    }

    /** The transfers panel on transfer [id]. */
    fun transfer(id: String): String = "/transfers?transfer=" + LandscapeNavigation.formEscape(id)

    /** The rows asked for by the context ([kind], [id]). */
    fun matching(rows: Series<TransferRow>, kind: String, id: String): Series<TransferRow> =
        rows.filter { it.context?.kind == kind && it.context.id == id }
}

/**
 * The transfers on the ground ([GroundScene]): a transfer stands in the room of the class its content is typed by
 * (the untyped room without one), its sources round it and its pieces about its foot; each protocol is a territory
 * induced under SUMO's [NETWORK] with its transports' territories inside it, and a ground line joins each transfer to
 * its protocol's territory. [figure] is the rollup of everything.
 */
object TransferGround {
    /** The SUMO class a protocol's territory is induced under: communication over a computer network. */
    const val NETWORK = "NetworkCommunication"

    fun color(state: String): Rgba = Rgba.parse(when (state) {
        "active" -> ActivityState.OPERATIONAL.color
        "waiting" -> ActivityState.WAITING.color
        "complete" -> ActivityState.COMPLETED.color
        "error" -> ActivityState.BLOCKED.color
        "paused" -> ActivityState.INERT.color
        else -> ActivityState.UNKNOWN.color
    })

    fun bytes(b: Double): String = Terrain.fmtBytes(b)
    /** [n] and [noun], the noun plural unless [n] is 1. */
    fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
    fun rate(down: Double, up: Double): String = "↓ ${bytes(down)}/s ↑ ${bytes(up)}/s"

    /** "done of total", or the bytes done alone while the total is unknown. */
    fun done(completed: Double, total: Double?): String =
        if (total == null) "${bytes(completed)} of unknown size" else "${bytes(completed)} of ${bytes(total)} (${Terrain.fixed(if (total > 0) 100 * completed / total else 0.0, 1)}%)"

    fun share(row: TransferRow): Double? = if (row.state == "complete") 1.0 else row.total?.let { if (it > 0) row.completed / it else null }

    fun stands(rows: Series<TransferRow>): Series<Stand> = rows α { r ->
        val path: Series<String> = r.`class`?.let { s_[it] } ?: emptySeriesOf()
        val label = ArrayList<String>()
        label.add(r.name.ifEmpty { r.id }); label.add("${r.state} · ${r.protocol}")
        label.add(done(r.completed, r.total)); label.add(rate(r.down, r.up) + " · " + count(r.sources.size, "source"))
        r.pieces?.let { p -> label.add("pieces ${p.have} of ${p.count} × ${bytes(p.length)}") }
        if (r.state == "error" && r.error != null) label.add("error: " + r.error)
        r.`class`?.let { label.add("class $it") } ?: label.add("untyped")
        Stand(r.id, path, label.toSeries(), HEIGHT, share(r), color(r.state),
            satellites = r.sources α { s ->
                Stand(r.id + "/" + s.address, path, s_[s.address, "${s.protocol}/${s.transport} · ${s.state}", rate(s.down, s.up), "verified ${bytes(s.completed)}"],
                    SOURCE_HEIGHT, 1.0, color(if (s.down + s.up > 0) "active" else s.state), noun = "sources")
            },
            marks = r.pieces?.count ?: 0, held = r.pieces?.let { if (it.count > 0) it.have.toDouble() / it.count else 0.0 } ?: 0.0,
            noun = "transfers")
    }

    /** A territory for each protocol among the rows and their sources, holding one for each transport its sources use. */
    fun territories(rows: Series<TransferRow>): Series<Territory> {
        val all = ArrayList<TransferSource>()
        for (r in rows.view) for (s in r.sources.view) all.add(s)
        val named = LinkedHashSet<String>()
        for (r in rows.view) if (r.protocol.isNotEmpty()) named.add(r.protocol)
        for (s in all) if (s.protocol.isNotEmpty()) named.add(s.protocol)
        val out = ArrayList<Territory>()
        for (p in named.sorted()) {
            val roll = TransferRollup.of(rows.filter { it.protocol == p })
            out.add(Territory("protocol/$p", s_[NETWORK, p], s_[p, count(roll.transfers, "transfer") + " · ${done(roll.completed, if (roll.untotalled > 0) null else roll.total)}", rate(roll.down, roll.up)], PROTOCOL))
            val over = all.filter { it.protocol == p }
            for (t in over.map { it.transport }.filter { it.isNotEmpty() }.distinct().sorted()) {
                val on = over.filter { it.transport == t }
                out.add(Territory("protocol/$p/$t", s_[NETWORK, p, t], s_["$p over $t", count(on.size, "source"), rate(on.sumOf { it.down }, on.sumOf { it.up })], TRANSPORT))
            }
        }
        return out.toSeries()
    }

    /** Each transfer joined to its protocol's territory. */
    fun links(rows: Series<TransferRow>): Series<Join<String, String>> = rows.filter { it.protocol.isNotEmpty() } α { it.id j "protocol/${it.protocol}" }

    /** The rollup of everything: the first figure seen from far away. */
    fun figure(rows: Series<TransferRow>): Series<String> {
        val r = TransferRollup.of(rows)
        if (r.transfers == 0) return s_["Transfers · none", "no transport reports a transfer"]
        return s_["Transfers · ${r.transfers}",
            "${bytes(r.completed)} done of ${bytes(r.total)}" + (if (r.untotalled > 0) " (+${r.untotalled} of unknown size)" else ""),
            rate(r.down, r.up),
            "waiting ${r.waiting} · active ${r.active} · paused ${r.paused} · complete ${r.complete} · failed ${r.failed}"]
    }

    /** Puts [rows] on [scene]. */
    fun show(scene: GroundScene, rows: Series<TransferRow>) {
        scene.stands = stands(rows); scene.territories = territories(rows); scene.links = links(rows); scene.figure = figure(rows)
    }

    const val HEIGHT = 2.6
    const val SOURCE_HEIGHT = 1.4
    val PROTOCOL = Rgba(120, 200, 190)
    val TRANSPORT = Rgba(150, 170, 230)
}
