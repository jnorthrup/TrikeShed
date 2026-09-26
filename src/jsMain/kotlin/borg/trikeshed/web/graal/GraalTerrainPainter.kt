package borg.trikeshed.web.graal

import borg.trikeshed.graal.console.Camera
import borg.trikeshed.graal.console.Sensitive
import borg.trikeshed.graal.console.Skin
import borg.trikeshed.graal.console.Terrain
import borg.trikeshed.graal.console.TerrainNode
import borg.trikeshed.graal.console.Topology
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The treemap tile painter shared by the Graal console and the extracted object-viewer terrain:
 * fill/stroke per skin, hover, heat glow, layer 2 (kolmogorov gzip ratio, fetched lazily per tile),
 * layer 3 (causal seq + ⟳gen), labels, byte subtitles, id tails, text previews and child share bars.
 * [topology] true paints the graal-terrain variant: category mask, category stripe, runtime labels,
 * no content fetches for runtime rows, layout that shrinks the running total per row.
 */
class TerrainPainter(
    private val contentUrl: (String) -> String,
    private val topology: Boolean,
    private val loaded: () -> Unit,
) {
    /** id → gzip ratio 0..1 (the practical K estimate); -1 in flight, -2 unavailable. */
    val kCache = LinkedHashMap<String, Double>()
    private var kInFlight = 0
    /** id → first lines, empty while loading, null when not text. */
    val previewCache = LinkedHashMap<String, List<String>?>()
    private var pInFlight = 0

    private val TEXTY = Regex("\\.(kt|kts|java|py|js|ts|md|json|yaml|yml|css|sh|gradle|xml|txt|html|toml|properties|sql|rs|c|h|cpp|go|wal|jsonl)$", RegexOption.IGNORE_CASE)
    private val HEXY = Regex("^[0-9a-f]{16,}(\\.\\w+)?$")

    private fun runtime(n: TerrainNode): Boolean = topology && n.detail != null && truthy(n.detail.asDynamic().runtime)

    fun wantK(n: TerrainNode) {
        if (runtime(n)) return
        val id = n.id ?: return
        if (Sensitive.docId(id)) { kCache[id] = -2.0; return }
        if (kCache.containsKey(id) || kInFlight >= 3 || n.bytes > 1500000 || !hasCompressionStream()) return
        kCache[id] = -1.0; kInFlight++
        val f: dynamic = window.asDynamic().fetch(contentUrl(id))
        f.then({ r: dynamic -> if (truthy(r.ok)) r.arrayBuffer() else js("Promise.reject(r.status)") })
            .then({ buf: dynamic ->
                gzipBytes(buf).then { c -> kCache[id] = if (num(buf.byteLength) > 0) min(1.0, num(c.byteLength) / num(buf.byteLength)) else 0.0 }
            })
            .catch({ _: dynamic -> kCache[id] = -2.0 })
            .finally({ kInFlight--; loaded() })
    }

    fun wantPreview(n: TerrainNode) {
        if (runtime(n)) return
        val id = n.id ?: return
        if (previewCache.containsKey(id) || pInFlight >= 3) return
        if (Sensitive.docId(id)) { previewCache[id] = null; return }
        if (!TEXTY.containsMatchIn(id) || n.bytes > 400000) { previewCache[id] = null; return }
        previewCache[id] = emptyList(); pInFlight++
        val f: dynamic = window.asDynamic().fetch(contentUrl(id))
        f.then({ r: dynamic -> if (truthy(r.ok)) r.text() else js("Promise.reject(r.status)") })
            .then({ t: dynamic -> previewCache[id] = t.unsafeCast<String>().split("\n").take(16) })
            .catch({ _: dynamic -> previewCache[id] = null })
            .finally({ pInFlight--; loaded() })
        if (previewCache.size > 400) { val k = previewCache.keys.first(); previewCache.remove(k) }
    }

    /** Everything a frame needs to paint one tile. */
    class Frame(
        val ctx: CanvasRenderingContext2D, val cam: Camera, val width: Double, val height: Double,
        val skin: Skin, val root: TerrainNode, val hover: TerrainNode?, val fog: Boolean,
        val heatOf: (TerrainNode) -> Double, val layer: Int, val maxSeq: Long, val mask: Int,
    )

    private fun px(v: Double): String = str(v) + "px"

    fun drawNode(f: Frame, n: TerrainNode, depth: Int) {
        if (topology && (n.categories and f.mask) == 0) return
        if (!n.placed) return
        val ctx = f.ctx; val cam = f.cam; val S = f.skin
        val x = (n.x - cam.ox) * cam.s; val y = (n.y - cam.oy) * cam.s; val w = n.w * cam.s; val h = n.h * cam.s
        if (x > f.width || y > f.height || x + w < 0 || y + h < 0 || w < 1.2 || h < 1.2) return
        val light = if (n.leaf) 18 else 10 + min(depth * 2, 8)
        val heat = f.heatOf(n)
        val fogged = f.fog && depth > 0 && heat <= 0
        if (fogged) ctx.globalAlpha = 0.22
        val glow = heat
        ctx.fillStyle = S.fill(n.hue, light)
        ctx.strokeStyle = S.stroke(n.hue, light)
        ctx.lineWidth = if (n === f.hover) 2.0 else S.lw
        ctx.beginPath(); ctx.rect(x, y, w, h); ctx.fill(); ctx.stroke()
        if (topology && n.leaf && w > 8 && h > 8) {
            ctx.fillStyle = Topology.first(n.categories)?.color ?: S.sub
            ctx.fillRect(x + 1, y + 1, min(3.0, w - 2), h - 2)
        }
        if (n === f.hover) { ctx.strokeStyle = S.hot(n.hue); ctx.stroke() }
        val base = if (fogged) 0.22 else 1.0
        if (glow > 0 && depth > 0) {
            ctx.globalAlpha = base * glow * 0.8; ctx.strokeStyle = S.hot(n.hue); ctx.lineWidth = 2.0
            ctx.strokeRect(x + 1, y + 1, w - 2, h - 2); ctx.globalAlpha = base
        }
        // ── layers: kolmogorov (2) and causal (3) tint the same cursor ──
        if (f.layer == 2 && n.leaf && w > 14 && h > 10) {
            wantK(n)
            val k = kCache[n.id]
            if (k != null && k >= 0) {
                // structured→green calm, incompressible→red noise
                ctx.globalAlpha = base * 0.5
                ctx.fillStyle = if (k > 0.92) "#ff4f58" else if (k > 0.7) "#ffb02e" else "#3ddc84"
                ctx.fillRect(x, y, w, max(2.0, h * 0.12))
                if (k > 0.92 && w > 30) {
                    ctx.globalAlpha = 0.18
                    var i = 0
                    while (i < min(60.0, w * h / 220)) { ctx.fillRect(x + Random.nextDouble() * w, y + Random.nextDouble() * h, 1.4, 1.4); i++ }
                }
                ctx.globalAlpha = base
                if (w > 70 && h > 28) {
                    ctx.font = "9px monospace"; ctx.fillStyle = if (k > 0.92) "#ff8a8f" else S.sub
                    ctx.fillText(toFixed(k * 100, 0) + "% K", x + w - 38, y + h - 5)
                }
            }
        }
        if (f.layer == 3 && w > 14 && h > 10) {
            val seq = if (n.leaf) n.seq else n.maxSeq
            if (seq > 0) {
                val age = seq.toDouble() / f.maxSeq // 0 old → 1 newest
                ctx.globalAlpha = base * 0.45
                ctx.fillStyle = "hsl(" + str(200 - 160 * age) + " 70% 50%)"
                ctx.fillRect(x, y + h - max(2.0, h * 0.12), w, max(2.0, h * 0.12))
                ctx.globalAlpha = base
                if (n.leaf && n.gen > 1 && w > 44) { ctx.font = "9px monospace"; ctx.fillStyle = "#ffb02e"; ctx.fillText("⟳" + n.gen, x + w - 26, y + 12) }
                if (n.leaf && w > 90 && h > 28) { ctx.font = "9px monospace"; ctx.fillStyle = S.sub; ctx.fillText("seq $seq", x + 5, y + h - 5) }
            }
        }
        val showKids = n.children.isNotEmpty() && w > 26 && h > 18
        if (showKids) { Terrain.layout(n, f.root, topology); for (c in n.children.values) drawNode(f, c, depth + 1) }
        if (w > 64 && h > 15) {
            ctx.font = (if (depth < 2) "bold " else "") + px(min(13.0, max(9.0, h * 0.08))) + " monospace"
            ctx.fillStyle = if (depth < 2) S.label(n.hue) else S.text
            val hexName = if (HEXY.matches(n.name)) "⬡" + n.name.take(8) else n.name
            val nm = if (topology) {
                val detailName: dynamic = if (n.detail != null) n.detail.asDynamic().name else null
                val runtimeLabel = if (n.name.startsWith("runtime:")) Topology.categories.firstOrNull { it.id == n.name.substring(8) }?.label else null
                if (truthy(detailName)) str(detailName) else runtimeLabel ?: hexName
            } else hexName
            val label = nm + (if (showKids) "" else if (n.leaf) "" else " ·" + n.docs)
            ctx.fillText(label.take(floor(w / 7).toInt()), x + 5, y + 12)
            if (!showKids && h > 34) {
                ctx.font = "9px monospace"; ctx.fillStyle = S.sub
                ctx.fillText(if (n.leaf) Terrain.fmtBytes(n.bytes) else loc(n.docs) + " docs · " + Terrain.fmtBytes(n.bytes), x + 5, y + 24)
            }
            if (n.leaf && w > 170 && h > 56) {
                ctx.font = "9px monospace"; ctx.fillStyle = S.faint
                ctx.fillText((n.id ?: "").takeLast(floor(w / 6).toInt()), x + 5, y + h - 6)
            }
            if (n.leaf && w > 210 && h > 90) {
                wantPreview(n)
                val lines = previewCache[n.id]
                if (!lines.isNullOrEmpty()) {
                    ctx.font = "8px ui-monospace,monospace"; ctx.fillStyle = S.faint
                    val maxL = min(lines.size, floor((h - 46) / 9).toInt())
                    for (i in 0 until maxL) ctx.fillText(lines[i].take(max(0, floor((w - 12) / 4.6).toInt())), x + 6, y + 38 + i * 9)
                }
            }
            if (!n.leaf && !showKids && w > 120 && h > 44) {
                val kids = n.children.values.sortedByDescending { it.bytes }.take(3)
                var off = 0.0
                ctx.font = "8px monospace"
                for (k in kids) {
                    val bw = (w - 12) * (k.bytes / (if (n.bytes != 0.0) n.bytes else 1.0))
                    ctx.fillStyle = S.stroke(k.hue, 30); ctx.globalAlpha = base * 0.7
                    ctx.fillRect(x + 6 + off, y + h - 14, max(2.0, bw), 5.0); ctx.globalAlpha = base
                    off += bw + 2
                }
            }
        }
        if (fogged) ctx.globalAlpha = 1.0
    }
}
