package borg.trikeshed.forge

import borg.trikeshed.common.Files
import borg.trikeshed.kanban.ForgeKanbanIngest
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
 * Browser mode: renders the baked HTML into the document. The persistence
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

    val html = ForgeApp.renderHtml()
    if (js("typeof window !== 'undefined' && typeof document !== 'undefined'") as Boolean) {
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
