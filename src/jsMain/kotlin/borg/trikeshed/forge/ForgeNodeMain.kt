package borg.trikeshed.forge

import borg.trikeshed.common.Files
import borg.trikeshed.kanban.ForgeKanbanIngest
import borg.trikeshed.lib.cascade.fibTicks
import borg.trikeshed.lib.toList
import borg.trikeshed.lib.α
import borg.trikeshed.media.boxes
import borg.trikeshed.media.ocrPrepassRgba
import borg.trikeshed.media.officeText
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * JS entry point for the Forge local-first workspace.
 *
 * Node.js mode: ingests `/tmp/hi` via [ForgeKanbanIngest.persistMarkdown],
 * then emits the full HTML with the reduction baked into the seed JSON.
 * The seed carries all 13 work packages, Rete facts, causal graph,
 * correlations, and Kanban cards so the browser can hydrate from it.
 *
 * Browser mode: if the document already has `#forge-seed` (Pages / JVM-baked shell) this is hydrate-only;
 * otherwise (bare webpack dev bundle) renders the HTML into the document.
 *
 * Entry-point note: several `fun main()`s exist on the JS classpath (this one, `viewserver.NodeViewServer`, …);
 * the compiler's JsMainFunctionDetector keeps the one in the lexicographically smallest package, which is
 * `borg.trikeshed.forge`. Adding a `main` in a package that sorts earlier would silently hijack the bundle. The persistence
 * script in [ForgePersistenceScript] hydrates from IndexedDB → localStorage
 * → seed and saves mutations back to localStorage + IndexedDB permanently.
 */
fun main() {
    // Ingest is a suspending contract (the Rete assertion pass suspends) and Kotlin/JS has
    // no runBlocking, so the whole entry point runs as a single coroutine on the JS event
    // loop.  One coroutine keeps the persist -> render ordering that the seed JSON depends on.
    MainScope().launch {
        try {
            forgeNodeMain()
        } catch (t: Throwable) {
            // The detached coroutine cannot propagate out of main(); mark the process failed
            // so `node forge.js > out.html` still aborts a pipeline instead of emitting nothing.
            System.err.println("Forge node main failed: ${t.message}")
            js("if (typeof process !== 'undefined') { process.exitCode = 1; }")
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private suspend fun forgeNodeMain() {
    // Node.js: ingest /tmp/hi into the local-first persistence layer.
    if (!(js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean)) {

        // Node.js: ingest /tmp/hi into the local-first persistence layer.
        // This reads all 1349 lines, parses the 13 work packages (G0..C1),
        // builds Rete facts + causal graph + Kanban cards, and persists the
        // source envelope to ~/.local/reactor/kanban/jim.json so that
        // defaultForgeAppState() can load and bake the full reduction into
        // the seed JSON that the browser's localStorage will adopt.
        val markdownPath = "/tmp/hi"
        if (Files.exists(markdownPath)) {
            val reduction = ForgeKanbanIngest.persistMarkdown("jim", markdownPath)
            val cardCount = reduction.board.cards.size
            val factCount = reduction.reteFacts.size
            val causalCount = reduction.causalNodes.size
            val linkCount = reduction.reteFacts.count { it.fields["kind"] == "link" }
            System.err.println(
                "Forge ingest: $markdownPath → $cardCount cards, " +
                    "$factCount Rete facts ($linkCount links), $causalCount causal nodes"
            )
        }
    }

    val inBrowser = js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean
    if (inBrowser && (js("document.getElementById('forge-seed') !== null") as Boolean)) {
        // GitHub Pages / JVM server: the page already carries the baked seed (ForgeBakePages). In a browser
        // loadProjection has no fs and would fall back to the demo seed, so never document.write over it —
        // the bundle is hydrate-only here and just announces itself to script.js.
        // The page's Shape view calls these; the alphabet, gate and box walker live in commonMain only.
        val runs: (String) -> Array<String> = { md -> (ForgeKanbanIngest.planRuns(md) α { "${it.a}:${it.b.a}:${it.b.b}" }).toList().toTypedArray() }
        val isPlan: (String) -> Boolean = ForgeKanbanIngest::isPlan
        val fib: (Int) -> Array<Int> = { n -> fibTicks(n).toList().toTypedArray() }
        val boxes: (ByteArray) -> Array<String> = { b -> (b.boxes() α { "${it.a.toList().joinToString("/")}:${it.b.sum.toLong()}" }).toList().toTypedArray() }
        val office: (ByteArray) -> Promise<String> = { b -> GlobalScope.promise { b.officeText(::inflateRaw) } }
        val prepass: (ByteArray) -> ByteArray = { it.ocrPrepassRgba() }
        js("window.forgeKotlin = Object.assign(window.forgeKotlin || {}, { loaded: true, runs: runs, isPlan: isPlan, fib: fib, boxes: boxes, office: office, prepass: prepass })")
        return
    }
    val html = ForgeApp.renderHtml()
    if (inBrowser) {
        renderBrowser(html)
    } else {
        println(html)
    }
}


private fun renderBrowser(html: String) {
    js(
        "document.open(); document.write(html); document.close();"
    )
}

/** stderr print for Node.js — avoids polluting stdout HTML output */
private object System {
    object err {
        fun println(msg: String) {
            js("typeof process !== 'undefined' && process.stderr && process.stderr.write(msg + '\\n')")
        }
    }
}

/** Raw deflate via the browser's DecompressionStream — the `inflate` for [officeText] on this target. */
private suspend fun inflateRaw(data: ByteArray): ByteArray {
    val buf = js("new Response(new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer()")
        .unsafeCast<Promise<ArrayBuffer>>().await()
    return Int8Array(buf).unsafeCast<ByteArray>()
}
