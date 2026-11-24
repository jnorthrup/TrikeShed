package narchy.spacegraph

import borg.trikeshed.lcnc.Expectation
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.Challenge
import borg.trikeshed.narsese.IngestStages
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

/**
 * The ingest stages as a machine a claim drops through. A stage is a pocket guarded by pins: a pin is one thing the
 * stage wants from the passage for the claim in play, and a thing the passage lacks is a gap in the guard. A ball is a
 * claim with its known outcome. It bounces across each stage's pins and goes on; at the first gap it falls into that
 * stage's pocket. A ball no stage catches lands in the tray. Where a ball rests is the run's own blame; the motion,
 * the reason shown at each contact ([IngestStages.reason]) and the payout are functions of the run and the time alone.
 *
 * Everything is laid out in a 1500 by 900 space and scaled into the viewport whole; no word is smaller than
 * [SMALL] of that space, and nothing is read by moving closer.
 */
object StageBoard {
    const val WIDE = 1500.0
    const val TALL = 900.0
    const val SMALL = 15.0

    // The cabinet and its field.
    private const val FX0 = 44.0
    private const val FX1 = 796.0
    private const val FY0 = 70.0
    private const val FY1 = 866.0
    private const val MID = 406.0
    private const val TRAY = 806.0
    private const val FEED = 774.0
    private const val CUP = 190.0
    private const val DEPTH = 30.0
    private const val BALL = 14.0
    private const val PIN = 6.5
    private val SIDE = doubleArrayOf(-172.0, 150.0, -150.0, 172.0, -178.0, 142.0, -146.0, 176.0, -166.0, 156.0)
    // The column that explains.
    private const val CX0 = 844.0
    private const val CX1 = 1480.0
    private val PLACES = listOf("back" to 52.0, "play" to 64.0, "next" to 52.0, "again" to 52.0, "skip" to 52.0, "1" to 50.0, "0.5" to 50.0, "0.1" to 58.0, "new" to 132.0)

    private val NIGHT = Rgba(11, 13, 18)
    private val LACQUER = Rgba(150, 24, 36)
    private val LACQUER_DEEP = Rgba(84, 12, 22)
    private val GOLD = Rgba(232, 186, 80)
    private val FELT = Rgba(13, 38, 50)
    private val BRASS = Rgba(214, 170, 72)
    private val BRASS_DIM = Rgba(120, 98, 48)
    private val CHROME = Rgba(214, 222, 232)
    private val INK = Rgba(243, 241, 234)
    private val DIM = Rgba(156, 168, 182)
    private val GO = Rgba(64, 214, 150)
    private val STOP = Rgba(255, 104, 96)
    private val PERSON = Rgba(255, 204, 84)
    private val MODEL = Rgba(104, 208, 255)
    private val PAPER = Rgba(244, 235, 214)
    private val PRINT = Rgba(42, 37, 32)
    private val MARKER = Rgba(255, 214, 64)
    private val CARD = Rgba(22, 28, 38)
    private val EDGE = Rgba(60, 72, 90)

    /** The stages output in [value]: [value] itself when it holds a board, else the output of a run record that does. */
    @Suppress("UNCHECKED_CAST")
    fun output(value: Any?): Map<String, Any?> {
        val m = value as? Map<*, *> ?: throw IllegalArgumentException("neither a stages output nor a run record")
        if (m["board"] is List<*>) return m as Map<String, Any?>
        val outputs = m["outputs"] as? Map<*, *> ?: throw IllegalArgumentException("no board and no outputs")
        return outputs.values.firstOrNull { (it as? Map<*, *>)?.get("board") is List<*> } as? Map<String, Any?>
            ?: throw IllegalArgumentException("no output holds a board")
    }

    /** The passage a run [record] read: its passage node's text, or its text node's value; empty when it holds neither. */
    fun passage(record: Any?): String {
        val outputs = (record as? Map<*, *>)?.get("outputs") as? Map<*, *>
        return ((outputs?.get("passage") as? Map<*, *>)?.get("text") ?: (outputs?.get("text") as? Map<*, *>)?.get("value"))?.toString().orEmpty()
    }

    /** One thing a stage wants for a claim: [held] by the passage or a gap; [word] when it is a word of the claim. */
    class Pin(val word: String?, val held: Boolean)

    /** A claim in the machine: its board row, the challenge it answers, and where the run rests it. */
    class Ball(val index: Int, val claim: String, val author: String, val challenge: Challenge?, val evidence: Map<String, Map<*, *>>,
               val blame: String?, val variants: Map<String, String?>) {
        val found get() = blame == null
        /** What the ball pays: its confidence when the machine's result is its owner's known outcome, minus it otherwise; null with no known outcome. */
        val paid: Double? = challenge?.let { if (it.states == found) it.confidence else -it.confidence }
        var start = 0.0
        var end = 0.0
        val ways = ArrayList<Way>()
        /** When the ball touched each pin of each stage it met: stage index to times, in the guard's order. */
        val hits = HashMap<Int, DoubleArray>()
    }

    /** A place the ball in play is at [t], reached over an arc [lift] high. */
    class Way(val t: Double, val x: Double, val y: Double, val lift: Double = 0.0)

    /** A ball meeting a stage ([stage] is -1 for its result): the approach begins at [start], it touches at [at], is settled at [settled] and is left at [end]. */
    class Contact(val ball: Int, val stage: Int, val passed: Boolean, val sentence: String, val start: Double, val at: Double, val settled: Double, val end: Double)

    /** One score line: an origin's balls the machine [agreed] with and [lost] to, and the confidence [net]. */
    class Tally(val origin: String, val agreed: Int, val lost: Int, val net: Double)

    /** The whole play of a stages [output] against [challenges] over [passage]: every ball's way and every contact, in time. */
    class Drop(val output: Map<String, Any?>, val challenges: List<Challenge>, val passage: String) {
        @Suppress("UNCHECKED_CAST")
        private val board = (output["board"] as? List<*> ?: throw IllegalArgumentException("a stages output holds a board")).map { it as Map<String, Any?> }
        val stages: List<String> = (board.firstOrNull()?.get("stages") as? Map<*, *>)?.keys?.map { it.toString() } ?: IngestStages.STAGES
        val balls: List<Ball> = Challenge.answers(board, challenges).mapIndexed { i, (row, challenge) ->
            Ball(i, row["claim"].toString(), row["author"].toString(), challenge,
                (row["stages"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to (it.value as? Map<*, *>).orEmpty() },
                row["blame"]?.toString(), (row["variants"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value?.toString()?.takeIf { v -> v != Challenge.FOUND } })
        }
        val contacts = ArrayList<Contact>()
        val end: Double

        fun gateX(stage: Int) = MID + SIDE[stage % SIDE.size]
        fun gateY(stage: Int) = FY0 + 12 + (TRAY - FY0 - 36) / stages.size * (stage + .5)
        /** The stage whose pocket holds [ball] at rest, or the last stage when it is found. */
        fun last(ball: Ball) = ball.blame?.let { stages.indexOf(it) } ?: (stages.size - 1)
        /** How many balls the run rests in the same place before [ball]. */
        fun earlier(ball: Ball) = balls.count { it.index < ball.index && it.blame == ball.blame }
        private fun slot(k: Int) = (if (k % 2 == 1) 1 else -1) * ((k + 1) / 2) * (BALL * 2 + 4)
        fun rest(ball: Ball): Vec3 = ball.blame?.let { b -> stages.indexOf(b).let { s -> Vec3(gateX(s) + slot(earlier(ball)), gateY(s) + DEPTH - BALL - 2) } }
            ?: Vec3(FX0 + 70 + earlier(ball) * (BALL * 2 + 6), FY1 - 16 - BALL)
        fun waiting(ball: Ball) = Vec3(FEED, TRAY - 20 - (balls.size - 1 - ball.index) * (BALL * 2 + 3))

        /** The guard of [stage] for [ball]: a pin for each thing the stage wants, a gap for each the passage lacks. */
        fun pins(ball: Ball, stage: Int): List<Pin> {
            val e = ball.evidence[stages[stage]].orEmpty()
            val pass = e["pass"] == true
            val wanted = ((e["wanted"] ?: e["claim"]) as? List<*>).orEmpty()
            val lacking = (e["lacking"] as? List<*>).orEmpty()
            val pins = wanted.map { w ->
                val word = w.toString().takeIf { s -> s.isNotEmpty() && s.all { it.isLetter() } }?.let { IngestStages.written(listOf(it), ball.claim)[0] }
                Pin(word, pass || w !in lacking)
            }
            return when {
                pins.isEmpty() || (!pass && pins.all { it.held }) -> listOf(Pin(null, pass))
                pins.size > 9 -> pins.filter { !it.held }.take(9).let { gaps -> pins.filter { it.held }.take(9 - gaps.size) + gaps }
                else -> pins
            }
        }

        fun pinX(stage: Int, count: Int, k: Int) = gateX(stage) + (k - (count - 1) / 2.0) * min(24.0, 150.0 / max(1, count - 1))
        fun pinY(stage: Int) = gateY(stage) - 18

        init {
            var t = 0.6
            for (ball in balls) {
                ball.start = t
                val from = waiting(ball)
                ball.ways.add(Way(t, from.x, from.y))
                t += .5; ball.ways.add(Way(t, FEED, FY0 + 34))
                t += .55; ball.ways.add(Way(t, MID, FY0 + 6, 26.0))
                var x = MID; var y = FY0 + 6
                var seed = ball.claim.fold(7) { h, c -> (h * 31 + c.code) and 0xffff }
                for (s in 0..last(ball)) {
                    val start = t
                    val pins = pins(ball, s)
                    val side = if (x <= gateX(s)) 1 else -1
                    val order = if (side > 0) pins.indices.toList() else pins.indices.reversed()
                    val top = pinY(s) - PIN - BALL
                    seed = (seed * 75 + 74) % 65537
                    val first = pinX(s, pins.size, order.first())
                    // One bounce on the field between the stages, then the stage's first pin.
                    t += .34; ball.ways.add(Way(t, (x + first) / 2 + (seed % 41 - 20), (y + top) / 2 + 6, 12.0))
                    t += .34; ball.ways.add(Way(t, first, top, 14.0))
                    val at = t
                    val hits = DoubleArray(pins.size) { Double.MAX_VALUE }
                    var caught = false
                    for ((n, k) in order.withIndex()) {
                        val px = pinX(s, pins.size, k)
                        if (n > 0) { t += .17; ball.ways.add(Way(t, px, top, 11.0)) }
                        hits[k] = t
                        if (!pins[k].held) {
                            val home = rest(ball)
                            t += .2; ball.ways.add(Way(t, px, gateY(s) + DEPTH - BALL - 2))
                            t += .25; ball.ways.add(Way(t, home.x, home.y, 5.0))
                            caught = true
                            break
                        }
                    }
                    ball.hits[s] = hits
                    val sentence = IngestStages.reason(stages[s], ball.evidence[stages[s]].orEmpty(), ball.claim)
                    val settled = t
                    t += hold(sentence)
                    contacts.add(Contact(ball.index, s, !caught, sentence, start, at, settled, t))
                    val here = ball.ways.last()
                    ball.ways.add(Way(t, here.x, here.y))
                    if (!caught) {
                        x = gateX(s) + side * (CUP / 2 + 20); y = gateY(s) + DEPTH + 8
                        t += .34; ball.ways.add(Way(t, x, y, 24.0))
                    }
                }
                val start = t
                val home = rest(ball)
                if (ball.found) {
                    t += .3; ball.ways.add(Way(t, (x + home.x) / 2, (y + home.y) / 2, 8.0))
                    t += .3; ball.ways.add(Way(t, home.x, home.y, 6.0))
                }
                val sentence = result(ball)
                contacts.add(Contact(ball.index, -1, ball.found, sentence, start, t, t, t + hold(sentence)))
                t += hold(sentence)
                ball.ways.add(Way(t, home.x, home.y))
                ball.end = t
                t += .4
            }
            end = t
        }

        /** How long a sentence stays up at full speed: long enough to read aloud. */
        fun hold(sentence: String) = max(2.6, sentence.split(' ').size / 2.8)

        fun where(ball: Ball) = ball.blame?.let { "caught at ${IngestStages.plain(it)}" } ?: "found at every stage"

        /** What the ball's rest means against its owner's known outcome. */
        fun result(ball: Ball): String {
            val c = ball.challenge ?: return "The ball is ${where(ball)}. No one gave it a known outcome, so it pays nothing."
            val owner = if (c.author == Expectation.PERSON) "A person" else c.model
            val said = "$owner said the passage ${if (c.states) "states this" else "does not state this"}"
            return if (c.states == ball.found) "The ball is ${where(ball)}. $said, and the machine agrees: it pays ${two(c.confidence)}."
            else if (c.author == Expectation.PERSON) "The ball is ${where(ball)}. $said, and a person's outcome is the truth: the machine is wrong${ball.blame?.let { " at ${IngestStages.plain(it)}" } ?: " to find it"}, and loses ${two(c.confidence)}."
            else "The ball is ${where(ball)}. $said: the machine disagrees, and the ball loses ${two(c.confidence)}."
        }

        /** The score of the balls at rest by [t], an origin a line. */
        fun tallies(t: Double): List<Tally> = balls.filter { it.end <= t && it.paid != null }.groupBy { it.challenge!!.origin }
            .map { (origin, held) -> Tally(origin, held.count { it.paid!! > 0 }, held.count { it.paid!! < 0 }, held.sumOf { it.paid!! }) }

        /** The contact in hand at [t]: the last one begun. */
        fun contact(t: Double): Contact? = contacts.lastOrNull { it.start <= t }

        /** Where [ball] is at [t]. */
        fun at(ball: Ball, t: Double): Vec3 {
            if (t <= ball.start) return waiting(ball)
            if (t >= ball.end) return rest(ball)
            val k = ball.ways.indexOfLast { it.t <= t }
            val a = ball.ways[k]; val b = ball.ways.getOrNull(k + 1) ?: return Vec3(a.x, a.y)
            val u = ((t - a.t) / (b.t - a.t)).coerceIn(0.0, 1.0)
            val fall = if (b.y >= a.y) u * u else 1 - (1 - u) * (1 - u)
            return Vec3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * fall - b.lift * 4 * u * (1 - u))
        }

        /** The times a step of the controls stops at: each contact settled, its sentence up. */
        val stops: List<Double> get() = contacts.map { it.settled + .05 }
    }

    fun two(v: Double): String = round(abs(v) * 100).toInt().let { "${if (v < 0) "−" else ""}${it / 100}.${(it % 100).toString().padStart(2, '0')}" }

    /** What each stage asks of the passage, said before the ball meets it. */
    fun question(stage: String): String = when (stage) {
        IngestStages.TOKEN -> "Does the passage use every word of the claim?"
        IngestStages.SECTION -> "Do those words stand together in one section?"
        IngestStages.SENTENCE -> "Does one sentence hold them all?"
        IngestStages.DEPENDENCY -> "Does that sentence join the words the way the claim does?"
        IngestStages.PARTY -> "Does the passage define the same parties?"
        IngestStages.CLAUSE -> "Does the passage have the same clause: who shall, must or may do what?"
        IngestStages.BEARER -> "Is the one the rule falls on the same kind of thing?"
        IngestStages.STATEMENT -> "Does the passage state the same rule, with the same force?"
        IngestStages.JOIN -> "Read together, do the passage and the claim restate one rule?"
        IngestStages.QUAD -> "Is the rule kept in the record?"
        else -> "Does the ${IngestStages.plain(stage)} stage find the claim?"
    }

    // ── drawing ──

    /** Collects a frame in the 1500 by 900 space and places it whole in a viewport. */
    private class Pen(viewport: Viewport, val mono: Double) {
        val s = min(viewport.width / WIDE, viewport.height / TALL)
        val ox = (viewport.width - WIDE * s) / 2
        val oy = (viewport.height - TALL * s) / 2
        val items = ArrayList<DrawItem>()
        fun p(x: Double, y: Double) = Vec3(ox + x * s, oy + y * s)
        fun rect(x: Double, y: Double, w: Double, h: Double) = Rect(ox + x * s, oy + y * s, w * s, h * s)
        private fun id() = "i${items.size}"
        fun path(parts: List<PathPart>, fill: Rgba? = null, stroke: Rgba? = null, width: Double = 1.0, dash: List<Double> = emptyList(), clip: Rect? = null, id: String = id()) {
            items.add(DrawItem.Path(id, parts.toSeries(), fill, stroke, width * s, dash.map { it * s }.toSeries(), clip = clip))
        }
        fun oval(x: Double, y: Double, rx: Double, ry: Double): List<PathPart> {
            val kx = rx * .5523; val ky = ry * .5523
            return listOf(PathPart.Move(p(x + rx, y)), PathPart.Cubic(p(x + rx, y + ky), p(x + kx, y + ry), p(x, y + ry)), PathPart.Cubic(p(x - kx, y + ry), p(x - rx, y + ky), p(x - rx, y)),
                PathPart.Cubic(p(x - rx, y - ky), p(x - kx, y - ry), p(x, y - ry)), PathPart.Cubic(p(x + kx, y - ry), p(x + rx, y - ky), p(x + rx, y)), PathPart.Close)
        }
        fun circle(x: Double, y: Double, r: Double, fill: Rgba? = null, stroke: Rgba? = null, width: Double = 1.0, dash: List<Double> = emptyList(), id: String = id()) =
            path(oval(x, y, r, r), fill, stroke, width, dash, id = id)
        fun box(x: Double, y: Double, w: Double, h: Double, r: Double): List<PathPart> = listOf(PathPart.Move(p(x + r, y)), PathPart.Line(p(x + w - r, y)),
            PathPart.Quadratic(p(x + w, y), p(x + w, y + r)), PathPart.Line(p(x + w, y + h - r)), PathPart.Quadratic(p(x + w, y + h), p(x + w - r, y + h)),
            PathPart.Line(p(x + r, y + h)), PathPart.Quadratic(p(x, y + h), p(x, y + h - r)), PathPart.Line(p(x, y + r)), PathPart.Quadratic(p(x, y), p(x + r, y)), PathPart.Close)
        fun panel(x: Double, y: Double, w: Double, h: Double, r: Double, fill: Rgba? = null, stroke: Rgba? = null, width: Double = 1.0, id: String = id()) =
            path(box(x, y, w, h, r), fill, stroke, width, id = id)
        fun line(points: List<Vec3>, stroke: Rgba, width: Double, dash: List<Double> = emptyList()) =
            path(points.mapIndexed { i, v -> if (i == 0) PathPart.Move(p(v.x, v.y)) else PathPart.Line(p(v.x, v.y)) }, stroke = stroke, width = width, dash = dash)
        fun text(text: String, x: Double, y: Double, size: Double, color: Rgba, weight: Int = 400, mono: Boolean = false, clip: Rect? = null, id: String = id()) {
            items.add(DrawItem.Text(id, text, p(x, y), color, size * s, if (mono) "monospace" else "sans-serif", clip = clip, weight = weight))
        }
        /** [text] in lines no wider than [width], drawn from the baseline [y] down; answers the baseline after the last. */
        fun lines(text: String, x: Double, y: Double, width: Double, size: Double, color: Rgba, weight: Int = 400, lead: Double = 1.32, most: Int = 99): Double {
            var at = y
            for (l in wrap(text, size, width, weight >= 600).take(most)) { text(l, x, at, size, color, weight); at += size * lead }
            return at
        }
        fun centred(text: String, x: Double, y: Double, size: Double, color: Rgba, weight: Int = 400) = text(text, x - wide(text, size, weight >= 600) / 2, y, size, color, weight)
    }

    /** The width a sans-serif [text] takes, near enough to break lines by. */
    fun wide(text: String, size: Double, bold: Boolean = false): Double = text.sumOf { c ->
        when {
            c in "ijl!'.,:;|I" -> .27
            c in "ftr()[]- “”‘’\"" -> .36
            c in "mwMW@" -> .86
            c.isUpperCase() -> .68
            c.isDigit() -> .57
            else -> .545
        }
    } * size * (if (bold) 1.07 else 1.0)

    fun wrap(text: String, size: Double, width: Double, bold: Boolean = false): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && wide(next, size, bold) > width) { out.add(line); line = word } else line = next
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private fun ball(pen: Pen, x: Double, y: Double, ball: Ball, alpha: Double = 1.0, ghost: Boolean = false) {
        val ring = (if (ball.author == Expectation.PERSON) PERSON else MODEL).copy(alpha = alpha)
        if (ghost) { pen.circle(x, y, BALL, Rgba(214, 222, 232, .16), ring.copy(alpha = .7), 1.6, listOf(4.0, 4.0)); return }
        pen.circle(x + 1.5, y + 3, BALL, Rgba(0, 0, 0, .35 * alpha))
        pen.circle(x, y, BALL, CHROME.copy(alpha = alpha), ring, 2.6)
        pen.circle(x - 4.5, y - 5, 4.2, Rgba(255, 255, 255, .85 * alpha))
        pen.centred("${ball.index + 1}", x, y + 5.5, 15.0, Rgba(20, 24, 32, alpha), 700)
    }

    /** A tick for a ball that paid, a cross for one that lost: the shape says it as well as the colour. */
    private fun mark(pen: Pen, x: Double, y: Double, paid: Double) {
        pen.circle(x, y, 8.0, if (paid > 0) GO else STOP, NIGHT, 1.5)
        if (paid > 0) pen.line(listOf(Vec3(x - 4, y), Vec3(x - 1, y + 3.4), Vec3(x + 4.4, y - 3.4)), NIGHT, 2.2)
        else { pen.line(listOf(Vec3(x - 3.4, y - 3.4), Vec3(x + 3.4, y + 3.4)), NIGHT, 2.2); pen.line(listOf(Vec3(x + 3.4, y - 3.4), Vec3(x - 3.4, y + 3.4)), NIGHT, 2.2) }
    }

    /** The controls on the machine, an id to its place in [viewport]: the buttons, and `strip`, the row of contacts. */
    fun controls(viewport: Viewport): List<Join<String, Rect>> = Pen(viewport, .6).let { pen ->
        var x = CX0
        PLACES.map { (id, w) -> (id j pen.rect(x, 838.0, w, 46.0)).also { x += w + 8 } } + ("strip" j pen.rect(CX0, 806.0, CX1 - CX0, 22.0))
    }

    /**
     * The machine at [t] seconds of [drop]'s play, in [viewport]; with no [drop], the machine standing empty over
     * [stages]. [playing] and [speed] set the look of the controls only. [mono] is the advance of the viewer's
     * monospace face, in ems: the passage's marked sentence is placed by it.
     */
    fun frame(drop: Drop?, viewport: Viewport, t: Double, playing: Boolean = false, speed: Double = 1.0, mono: Double = .6,
              stages: List<String> = drop?.stages ?: IngestStages.STAGES): FramePlan {
        val pen = Pen(viewport, mono)
        val count = stages.size
        fun gateX(s: Int) = MID + SIDE[s % SIDE.size]
        fun gateY(s: Int) = FY0 + 12 + (TRAY - FY0 - 36) / count * (s + .5)
        val inPlay = drop?.balls?.firstOrNull { t > it.start && t < it.end }
        val contact = drop?.contact(t)
        val mine = contact?.takeIf { inPlay != null && it.ball == inPlay.index }
        val over = drop != null && t >= drop.end

        // The cabinet: lacquer, a gold rim, the felt.
        pen.panel(20.0, 12.0, 800.0, 876.0, 40.0, LACQUER, GOLD, 3.0)
        pen.panel(28.0, 20.0, 784.0, 860.0, 34.0, stroke = LACQUER_DEEP, width = 5.0)
        pen.panel(FX0 - 6, FY0 - 6, FX1 - FX0 + 12, FY1 - FY0 + 12, 30.0, Rgba(6, 14, 20), GOLD.copy(alpha = .8), 2.0)
        pen.panel(FX0, FY0, FX1 - FX0, FY1 - FY0, 26.0, FELT)
        for (k in 0 until 4) pen.path(pen.oval(MID, (FY0 + TRAY) / 2, 330.0 - k * 70, 360.0 - k * 80), Rgba(60, 130, 150, .05))
        // The marquee.
        pen.text("PACHINKO", 54.0, 52.0, 27.0, GOLD, 800)
        val score = drop?.tallies(t).orEmpty()
        val marquee = when {
            drop == null -> "no ball in play"
            score.isEmpty() -> "${drop.balls.size} ${if (drop.balls.size == 1) "ball" else "balls"} to drop"
            else -> "paid ${score.sumOf { it.agreed }} · lost ${score.sumOf { it.lost }} · net ${if (score.sumOf { it.net } >= 0) "+" else ""}${two(score.sumOf { it.net })}"
        }
        pen.text(marquee, 790.0 - wide(marquee, 18.0, true), 50.0, 18.0, INK, 700)

        // The field of pins, clear of the pockets, the feed and the tray.
        val field = ArrayList<PathPart>(); val glint = ArrayList<PathPart>()
        var row = 0; var y = FY0 + 20
        while (y < TRAY - 8) {
            var x = FX0 + 18 + (if (row % 2 == 0) 0.0 else 13.0)
            while (x < FEED - 22) {
                val near = (0 until count).any { s -> abs(x - gateX(s)) < CUP / 2 + 10 && y > gateY(s) - 38 && y < gateY(s) + DEPTH + 30 }
                if (!near) { field.addAll(pen.oval(x, y, 2.5, 2.5)); glint.addAll(pen.oval(x - .8, y - .8, .9, .9)) }
                x += 26
            }
            y += 19; row++
        }
        pen.path(field, BRASS.copy(alpha = .62)); pen.path(glint, Rgba(255, 240, 190, .8))
        // The feed: a chrome channel up the right side, the balls waiting in it. The tray below.
        pen.panel(FEED - 18, FY0 + 22, 36.0, TRAY - FY0 - 26, 18.0, Rgba(4, 10, 14, .85), CHROME.copy(alpha = .5), 1.5)
        pen.panel(FX0 + 14, TRAY + 6, FX1 - FX0 - 28, FY1 - TRAY - 14, 20.0, Rgba(4, 10, 14, .9), CHROME.copy(alpha = .7), 2.0)
        val trayed = drop?.balls?.count { it.found && it.end <= t } ?: 0
        "found in the passage".let { pen.text(it, FX1 - 40 - wide(it, 16.0, true), FY1 - 25.0, 16.0, if (trayed > 0) GO else DIM, 700) }

        // The pockets and their guards.
        val reached = if (drop != null && inPlay != null && mine != null) (if (mine.stage >= 0) mine.stage else drop.last(inPlay)) else -1
        for (s in 0 until count) {
            val gx = gateX(s); val gy = gateY(s)
            val current = mine != null && mine.stage == s
            val touched = current && t >= mine!!.at
            val ends = mine != null && mine.stage < 0 && s == reached
            val caughtHere = touched && !mine!!.passed || ends && !inPlay!!.found
            val passedHere = s < reached || touched && mine!!.passed || ends && inPlay!!.found
            val resting = drop?.balls?.count { it.blame == stages[s] && it.end <= t } ?: 0
            val lip = if (caughtHere) STOP else if (passedHere) GO else if (resting > 0) STOP.copy(alpha = .7) else CHROME.copy(alpha = .55)
            if (current) pen.path(pen.oval(gx, gy + 6, CUP / 2 + 34, 58.0), (if (caughtHere) STOP else if (touched) GO else GOLD).copy(alpha = .10))
            // The pocket: a shallow cup with flared lips.
            pen.path(listOf(PathPart.Move(pen.p(gx - CUP / 2 - 8, gy - 6)), PathPart.Quadratic(pen.p(gx - CUP / 2, gy), pen.p(gx - CUP / 2 + 4, gy + 12)),
                PathPart.Quadratic(pen.p(gx - CUP / 2 + 10, gy + DEPTH), pen.p(gx - CUP / 2 + 30, gy + DEPTH)), PathPart.Line(pen.p(gx + CUP / 2 - 30, gy + DEPTH)),
                PathPart.Quadratic(pen.p(gx + CUP / 2 - 10, gy + DEPTH), pen.p(gx + CUP / 2 - 4, gy + 12)), PathPart.Quadratic(pen.p(gx + CUP / 2, gy), pen.p(gx + CUP / 2 + 8, gy - 6))),
                Rgba(4, 10, 14, .9), lip, if (current) 3.4 else 2.4)
            // The guard: the pins of the ball in play, each lit as it is touched; a gap is an empty socket.
            val armed = drop != null && inPlay != null && s <= drop.last(inPlay)
            val pins = if (armed) drop!!.pins(inPlay!!, s) else List(3) { Pin(null, true) }
            val hits = if (armed) inPlay!!.hits[s] else null
            for ((k, pin) in pins.withIndex()) {
                val px = gx + (k - (pins.size - 1) / 2.0) * min(24.0, 150.0 / max(1, pins.size - 1)); val py = gy - 18
                val hit = hits != null && t >= hits[k]
                if (!pin.held) {
                    if (hit) pen.circle(px, py, PIN + 7, STOP.copy(alpha = .22))
                    pen.circle(px, py, PIN + 1, null, if (hit) STOP else STOP.copy(alpha = .55), 1.8, listOf(3.0, 3.0))
                } else {
                    if (hit) pen.circle(px, py, PIN + 6, Rgba(255, 226, 130, .28))
                    pen.circle(px, py, PIN, if (hit) Rgba(255, 226, 130) else if (armed) BRASS else BRASS_DIM, Rgba(60, 44, 10), 1.0)
                    pen.circle(px - 2, py - 2, 2.0, Rgba(255, 255, 255, if (hit) .95 else .6))
                }
            }
            // The stage's plate.
            pen.centred("${s + 1}  ${IngestStages.plain(stages[s])}", gx, gy + DEPTH + 21, 16.0, if (current) INK else if (passedHere) GO else if (caughtHere) STOP else DIM, if (current) 800 else 600)
        }

        if (drop != null) {
            // The way the ball in play has come.
            inPlay?.let { b ->
                // From where it left the last stage to where it is.
                val trail = ArrayList<Vec3>(); var u = mine?.start ?: b.start
                while (u < t) { trail.add(drop.at(b, u)); u += .04 }
                trail.add(drop.at(b, t))
                if (trail.size > 1) pen.line(trail, (if (b.author == Expectation.PERSON) PERSON else MODEL).copy(alpha = .42), 2.0, listOf(2.0, 5.0))
                for (k in 1..7) drop.at(b, t - k * .028).let { pen.circle(it.x, it.y, BALL * (1 - k * .09), CHROME.copy(alpha = .16 - k * .018)) }
            }
            for (b in drop.balls) if (b !== inPlay) drop.at(b, t).let { at ->
                ball(pen, at.x, at.y, b, if (t <= b.start) .9 else 1.0)
                if (t >= b.end) b.paid?.let { mark(pen, at.x + 11, at.y - 11, it) }
            }
            // The same ball under the run's other settings, where it rests elsewhere.
            (inPlay?.takeIf { mine?.stage == -1 })?.let { b ->
                for (blamed in b.variants.values.distinct()) if (blamed != b.blame) {
                    val s = blamed?.let { stages.indexOf(it) }
                    if (s == null) ball(pen, FX1 - 70.0, FY1 - 16 - BALL, b, ghost = true) else if (s >= 0) ball(pen, gateX(s) + CUP / 2 - 22, gateY(s) + DEPTH - BALL - 2, b, ghost = true)
                }
            }
            inPlay?.let { b ->
                val at = drop.at(b, t)
                ball(pen, at.x, at.y, b)
                // The word of the pin it is on, or of the gap it fell through.
                if (mine != null && mine.stage >= 0 && t >= mine.at) {
                    val pins = drop.pins(b, mine.stage); val hits = b.hits.getValue(mine.stage)
                    val k = pins.indices.filter { t >= hits[it] }.maxByOrNull { hits[it] }
                    val label = k?.let { pins[it] }?.let { p -> p.word?.let { w -> if (p.held) w else "no “$w”" } }
                    if (label != null) {
                        val px = drop.pinX(mine.stage, pins.size, k); val w = wide(label, 16.0, true) + 22
                        val ly = drop.pinY(mine.stage) - 50
                        pen.panel(px - w / 2, ly - 20, w, 28.0, 14.0, if (pins[k].held) Rgba(255, 226, 130) else STOP, NIGHT, 1.5)
                        pen.text(label, px - w / 2 + 11, ly, 16.0, NIGHT, 700)
                    }
                }
            }
        }

        column(pen, drop, t, stages, contact, inPlay, over)
        bar(pen, drop, t, playing, speed, contact)
        return FramePlan(viewport, pen.items.toSeries(), NIGHT)
    }

    private fun owner(ball: Ball) = ball.challenge?.let { if (it.author == Expectation.PERSON) "a person" else it.model } ?: if (ball.author == Expectation.PERSON) "a person" else "a model"

    /** The column that explains: the ball in play, the contact and its reason, the passage; or the score when all balls rest. */
    private fun column(pen: Pen, drop: Drop?, t: Double, stages: List<String>, contact: Contact?, inPlay: Ball?, over: Boolean) {
        val w = CX1 - CX0
        if (drop == null) {
            pen.panel(CX0, 14.0, w, 778.0, 18.0, CARD, EDGE, 1.0)
            pen.text("The machine is empty.", CX0 + 26, 70.0, 30.0, INK, 700)
            var y = pen.lines("A ball is a claim about a passage, with what you know to be true of it: the passage states it, or it does not.", CX0 + 26, 118.0, w - 52, 20.0, INK, lead = 1.4)
            y = pen.lines("The ball drops through the ${stages.size} stages the passage is read by. Each stage is a pocket guarded by pins, one pin for each thing the stage needs. Where the passage lacks one there is a gap, and the ball falls in.", CX0 + 26, y + 14, w - 52, 20.0, DIM, lead = 1.4)
            y = pen.lines("A ball that passes every guard lands in the tray: the machine found the claim in the passage.", CX0 + 26, y + 14, w - 52, 20.0, DIM, lead = 1.4)
            pen.lines("Press “new ball” below, give it a passage and a claim, and drop it.", CX0 + 26, y + 26, w - 52, 22.0, GOLD, 700, 1.4)
            return
        }
        if (over) { score(pen, drop); return }
        val ball = inPlay ?: contact?.let { drop.balls[it.ball] }
        if (ball == null) {
            pen.panel(CX0, 14.0, w, 168.0, 18.0, CARD, EDGE, 1.0)
            pen.text("${drop.balls.size} ${if (drop.balls.size == 1) "ball" else "balls"} in the feed", CX0 + 22, 60.0, 26.0, INK, 800)
            pen.lines("Each is a claim about the passage below, with a known outcome. Press play, or step, to drop the first.", CX0 + 22, 96.0, w - 44, 19.0, DIM, lead = 1.35)
            passage(pen, drop, null, -1, false, 192.0)
            return
        }
        val c = contact?.takeIf { it.ball == ball.index }
        // The ball.
        pen.panel(CX0, 14.0, w, 168.0, 18.0, CARD, EDGE, 1.0)
        ball(pen, CX0 + 34, 46.0, ball)
        val known = ball.challenge?.let { "${owner(ball)} says the passage ${if (it.states) "states this" else "does NOT state this"} · confidence ${two(it.confidence)}" } ?: "from ${owner(ball)} · no known outcome"
        pen.text("Ball ${ball.index + 1} of ${drop.balls.size}", CX0 + 60, 42.0, 16.0, DIM, 700)
        pen.text(known, CX0 + 60, 63.0, 16.0, if (ball.author == Expectation.PERSON) PERSON else MODEL, 600)
        pen.lines(ball.claim, CX0 + 22, 100.0, w - 44, 24.0, INK, 700, 1.28, 3)
        // The contact.
        pen.panel(CX0, 192.0, w, 236.0, 18.0, CARD, EDGE, 1.0)
        if (c == null) { pen.text("Coming up the feed", CX0 + 22, 232.0, 22.0, DIM, 700); passage(pen, drop, ball, -1, false); return }
        val touched = t >= c.at
        val lost = c.stage < 0 && (ball.paid ?: 0.0) < 0
        val tone = if (!touched) GOLD else if (c.stage < 0) (ball.paid?.let { if (it > 0) GO else STOP } ?: DIM) else if (c.passed) GO else STOP
        pen.text(if (c.stage < 0) "RESULT" else "STAGE ${c.stage + 1} OF ${stages.size}", CX0 + 22, 222.0, 15.0, DIM, 700)
        val name = if (c.stage < 0) (if (ball.found) "In the tray" else "In the ${IngestStages.plain(ball.blame!!)} pocket") else IngestStages.plain(stages[c.stage]).replaceFirstChar { it.uppercase() }
        pen.text(name, CX0 + 22, 252.0, 26.0, INK, 800)
        val chip = when {
            !touched -> "falling"
            c.stage < 0 -> ball.paid?.let { if (it > 0) "pays ${two(it)}" else "loses ${two(-it)}" } ?: "no outcome"
            c.passed -> "bounces on"
            else -> "caught"
        }
        val cw = wide(chip, 17.0, true) + 46; val ix = CX1 - 22 - cw + 14
        pen.panel(CX1 - 22 - cw, 228.0, cw, 32.0, 16.0, tone)
        if ((touched && c.stage >= 0 && !c.passed) || lost) { pen.line(listOf(Vec3(ix, 238.0), Vec3(ix + 12, 250.0)), NIGHT, 2.6); pen.line(listOf(Vec3(ix + 12, 238.0), Vec3(ix, 250.0)), NIGHT, 2.6) }
        else pen.path(listOf(PathPart.Move(pen.p(ix, 237.0)), PathPart.Line(pen.p(ix + 13, 244.0)), PathPart.Line(pen.p(ix, 251.0)), PathPart.Close), NIGHT)
        pen.text(chip, ix + 20, 250.0, 17.0, NIGHT, 700)
        if (c.stage >= 0) pen.lines(question(stages[c.stage]), CX0 + 22, 284.0, w - 44, 17.0, DIM, lead = 1.3, most = 2)
        if (touched) pen.lines(c.sentence, CX0 + 22, if (c.stage >= 0) 318.0 else 292.0, w - 44, 20.0, INK, 600, 1.3, 5)
        // The leader: from the reason to the place on the machine it is about.
        val at = drop.at(ball, t)
        pen.path(listOf(PathPart.Move(pen.p(CX0, 300.0)), PathPart.Quadratic(pen.p(CX0 - 30, 300.0), pen.p(at.x + BALL + 6, at.y))), stroke = tone.copy(alpha = .7), width = 2.0)
        pen.circle(at.x, at.y, BALL + 6, null, tone, 2.4)
        passage(pen, drop, ball, c.stage, touched)
    }

    /** The passage on paper, the sentence the stages read for [ball] marked once the ball has met the sentence stage. */
    private fun passage(pen: Pen, drop: Drop, ball: Ball?, stage: Int, touched: Boolean, top: Double = 438.0) {
        val w = CX1 - CX0; val h = 792.0 - top
        pen.panel(CX0, top, w, h, 12.0, PAPER, Rgba(190, 176, 140), 1.0)
        pen.text("THE PASSAGE", CX0 + 20, top + 26, 15.0, Rgba(120, 104, 70), 800)
        val text = drop.passage.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        val size = 16.0; val advance = size * pen.mono; val lead = 23.0
        val cols = ((w - 40) / advance).toInt()
        // Lines of whole words, each with where it starts in the text.
        val lines = ArrayList<Join<Int, String>>(); var from = 0
        while (from < text.length) {
            var to = min(text.length, from + cols)
            if (to < text.length) to = text.lastIndexOf(' ', to).takeIf { it > from } ?: to
            lines.add(from j text.substring(from, to)); from = if (to < text.length && text[to] == ' ') to + 1 else to
        }
        val sentenceStage = drop.stages.indexOf(IngestStages.SENTENCE)
        val read = ball?.evidence?.get(IngestStages.SENTENCE)?.get("sentence")?.toString()?.split(Regex("\\s+"))?.joinToString(" ")
        val begin = if (read != null && sentenceStage >= 0 && (stage > sentenceStage || stage == sentenceStage && touched || stage == -1 && drop.last(ball) >= sentenceStage)) text.indexOf(read) else -1
        val finish = if (begin >= 0) begin + read!!.length else -1
        val room = ((h - 60) / lead).toInt()
        val first = if (begin < 0) 0 else lines.indexOfFirst { it.a + it.b.length > begin }.let { k -> (k - max(1, (room - lines.count { it.a < finish && it.a + it.b.length > begin }) / 2)).coerceIn(0, max(0, lines.size - room)) }
        val clip = pen.rect(CX0 + 8, top + 40, w - 16, h - 48)
        for ((n, l) in lines.drop(first).take(room).withIndex()) {
            val y = top + 62 + n * lead
            val a = max(begin, l.a) - l.a; val b = min(finish, l.a + l.b.length) - l.a
            val marked = begin >= 0 && a < b
            if (marked) pen.path(pen.box(CX0 + 20 + a * advance - 3, y - size + 1, (b - a) * advance + 6, lead - 1, 3.0), MARKER, clip = clip)
            pen.text(l.b, CX0 + 20, y, size, PRINT, 400, true, clip)
            if (marked) pen.text(l.b.substring(a, b), CX0 + 20 + a * advance, y, size, PRINT, 700, true, clip)
        }
        if (first > 0) pen.path(pen.box(CX0 + 1, top + 38, w - 2, 8.0, 0.0), PAPER.copy(alpha = .8))
        if (first + room < lines.size) pen.path(pen.box(CX0 + 1, top + h - 16, w - 2, 15.0, 0.0), PAPER.copy(alpha = .85))
    }

    /** All balls at rest: who was paid, and for each ball where it rests and why. */
    private fun score(pen: Pen, drop: Drop) {
        val w = CX1 - CX0
        pen.panel(CX0, 14.0, w, 778.0, 18.0, CARD, EDGE, 1.0)
        val scored = drop.balls.filter { it.paid != null }
        val people = scored.filter { it.author == Expectation.PERSON && it.paid!! < 0 }
        val head = when {
            scored.isEmpty() -> "No ball had a known outcome."
            people.isNotEmpty() -> "The machine lost ${people.size} of ${scored.count { it.author == Expectation.PERSON }} to people: " +
                people.groupBy { it.blame }.entries.joinToString(", ") { (stage, held) -> "${held.size} ${stage?.let { "at ${IngestStages.plain(it)}" } ?: "found wrongly"}" } + "."
            else -> "The machine agreed with ${scored.count { it.paid!! > 0 }} of ${scored.size} known outcomes."
        }
        var y = pen.lines(head, CX0 + 22, 54.0, w - 44, 26.0, INK, 800, 1.22)
        for (tally in drop.tallies(drop.end)) {
            val line = "${if (tally.origin == Expectation.PERSON) "people" else tally.origin}: paid ${tally.agreed}, lost ${tally.lost}, net ${if (tally.net >= 0) "+" else ""}${two(tally.net)}"
            pen.text(line, CX0 + 22, y + 4, 18.0, if (tally.origin == Expectation.PERSON) PERSON else MODEL, 700); y += 26
        }
        y += 10
        // Every ball is listed whole. Reasons are given for all when there is room, else for the balls the machine lost, else for none.
        fun why(b: Ball) = b.blame?.let { wrap(IngestStages.reason(it, b.evidence[it].orEmpty(), b.claim), 15.0, w - 100).take(3) }.orEmpty()
        fun tall(b: Ball, level: Int) = 16 + wrap(b.claim, 17.0, w - 100, true).size * 22 + 20 + (if (level == 0 || level == 1 && (b.paid ?: 0.0) < 0) why(b).size * 19 else 0) + 6
        val level = (0..2).firstOrNull { l -> y + drop.balls.sumOf { tall(it, l) } <= 786 } ?: 2
        for (b in drop.balls) {
            if (y + tall(b, level) > 790) break
            pen.line(listOf(Vec3(CX0 + 22, y - 6), Vec3(CX1 - 22, y - 6)), EDGE, 1.0)
            ball(pen, CX0 + 38, y + 20, b)
            b.paid?.let { mark(pen, CX0 + 49, y + 9, it) }
            var ly = y + 16
            for (l in wrap(b.claim, 17.0, w - 100, true)) { pen.text(l, CX0 + 66, ly, 17.0, INK, 700); ly += 22 }
            val said = b.challenge?.let { "said ${if (it.states) "it is stated" else "it is not stated"}" } ?: "no known outcome"
            val pay = b.paid?.let { if (it > 0) "paid ${two(it)}" else "lost ${two(-it)}" } ?: "unscored"
            pen.text("$said · ${drop.where(b)} · $pay", CX0 + 66, ly, 15.0, b.paid?.let { if (it > 0) GO else STOP } ?: DIM, 700); ly += 20
            if (level == 0 || level == 1 && (b.paid ?: 0.0) < 0) for (l in why(b)) { pen.text(l, CX0 + 66, ly, 15.0, DIM); ly += 19 }
            y = ly + 6
        }
    }

    /** The time controls: a tick a contact, the play head, and the buttons [controls] places. */
    private fun bar(pen: Pen, drop: Drop?, t: Double, playing: Boolean, speed: Double, contact: Contact?) {
        val w = CX1 - CX0
        pen.panel(CX0, 806.0, w, 22.0, 6.0, Rgba(16, 20, 28), EDGE, 1.0)
        if (drop != null && drop.contacts.isNotEmpty()) {
            val n = drop.contacts.size; val step = (w - 8) / n
            for ((k, c) in drop.contacts.withIndex()) {
                val x = CX0 + 4 + k * step
                val gone = c.settled <= t; val here = c === contact && t < drop.end
                val color = (if (c.stage < 0) GOLD else if (c.passed) GO else STOP).copy(alpha = if (here) 1.0 else if (gone) .75 else .28)
                pen.path(pen.box(x + 1, if (here) 808.0 else 811.0, max(2.0, step - 2), if (here) 18.0 else 12.0, 2.0), color)
                if (c.stage < 0 && k < n - 1) pen.line(listOf(Vec3(x + step, 807.0), Vec3(x + step, 827.0)), Rgba(110, 124, 144), 1.2)
            }
        }
        var x = CX0
        for ((id, bw) in PLACES) {
            val on = id == "play" && playing || id.toDoubleOrNull() == speed
            val live = drop != null || id == "new"
            pen.panel(x, 838.0, bw, 46.0, 12.0, if (id == "new") GOLD else if (on) Rgba(60, 76, 100) else Rgba(26, 32, 44), if (on) INK else Rgba(70, 84, 104), 1.4, id = "control-$id")
            val ink = if (id == "new") NIGHT else if (live) INK else Rgba(90, 100, 116)
            val cx = x + bw / 2; val cy = 861.0
            fun tri(ax: Double, dir: Int) = pen.path(listOf(PathPart.Move(pen.p(ax - 6.0 * dir, cy - 9)), PathPart.Line(pen.p(ax + 7.0 * dir, cy)), PathPart.Line(pen.p(ax - 6.0 * dir, cy + 9)), PathPart.Close), ink)
            fun stem(ax: Double) = pen.path(pen.box(ax - 1.8, cy - 9, 3.6, 18.0, 1.0), ink)
            when (id) {
                "back" -> { stem(cx - 9); tri(cx + 2, -1) }
                "next" -> { tri(cx - 2, 1); stem(cx + 9) }
                "play" -> if (playing) { pen.path(pen.box(cx - 8, cy - 9, 5.5, 18.0, 1.0), ink); pen.path(pen.box(cx + 2.5, cy - 9, 5.5, 18.0, 1.0), ink) } else tri(cx, 1)
                "again" -> { pen.path(pen.oval(cx, cy, 9.0, 9.0).dropLast(2), stroke = ink, width = 2.6); pen.path(listOf(PathPart.Move(pen.p(cx + 3, cy - 13)), PathPart.Line(pen.p(cx + 11, cy - 8)), PathPart.Line(pen.p(cx + 3, cy - 3)), PathPart.Close), ink) }
                "skip" -> { tri(cx - 7, 1); tri(cx + 6, 1) }
                "new" -> pen.centred("+ new ball", cx, cy + 6, 17.0, ink, 800)
                else -> pen.centred(when (id) { "1" -> "1×"; "0.5" -> "½×"; else -> "1/10×" }, cx, cy + 6, 16.0, ink, 700)
            }
            x += bw + 8
        }
    }
}
