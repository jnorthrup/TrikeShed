package borg.trikeshed.forge

import borg.trikeshed.kanban.ForgeBoardPersistence
import borg.trikeshed.kanban.ForgeKanbanIngest
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * JVM baker for the Forge PWA: renders [ForgeApp.renderHtml] with the real seed and writes it to
 * `docs/index.html` (the GitHub Pages root). Replaces the `jsNodeProductionRun | awk` pipeline,
 * which cannot run while `compileKotlinJs` carries commonMain JVM-only debt.
 *
 * Usage: `ForgeBakePages [outFile=docs/index.html] [donorMarkdown=/tmp/hi] [userId=jnorthrup] [bundles=./js/TrikeShed.js,...]`
 *
 * Seed precedence mirrors the Node entry point: if the donor markdown exists it is ingested and
 * persisted (`~/.local/reactor/kanban/<user>.json`) first, so the bake and the running server
 * agree; otherwise the persisted board is projected; otherwise the in-memory fallback reduction.
 */
object ForgeBakePages {
    @JvmStatic
    fun main(args: Array<String>) {
        val out: Path = Paths.get(args.getOrElse(0) { "docs/index.html" })
        val donor = args.getOrElse(1) { "/tmp/hi" }
        val userId = args.getOrElse(2) { "jnorthrup" }
        val bundles = args.getOrElse(3) { "" }.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val donorPath = Paths.get(donor)
        if (Files.isRegularFile(donorPath)) {
            // Validate BEFORE persisting: persistMarkdown writes ~/.local/reactor/kanban/<user>.json first
            // and only then parses, so an arbitrary /tmp/hi would clobber a good board with junk.
            val markdown = Files.readString(donorPath)
            val probe = runCatching { ForgeKanbanIngest.project(ForgeBoardPersistence.source(userId, markdown, donor)) }
            probe.fold(
                onSuccess = {
                    val reduction = runBlocking { ForgeKanbanIngest.persistMarkdown(userId, donor) }
                    val links = reduction.reteFacts.count { it.fields["kind"] == "link" }
                    System.err.println(
                        "forge-bake: ingested $donor → ${reduction.board.cards.size} cards, " +
                            "${reduction.reteFacts.size} Rete facts ($links links), ${reduction.causalNodes.size} causal nodes"
                    )
                },
                onFailure = { e ->
                    System.err.println("forge-bake: $donor is not a kanban plan (${e.message}); persisted board for '$userId' left untouched")
                },
            )
        } else {
            System.err.println("forge-bake: no donor at $donor; using persisted board for '$userId' (or fallback)")
        }

        // The docs mindmap is read from the same directory the page is written into, so the map
        // cannot describe a document that was deleted or miss one that was added. Sorted, because
        // a directory listing is not ordered and the picture must not shuffle between bakes.
        val docsDir = out.parent ?: Paths.get("docs")
        val docs = runCatching {
            Files.list(docsDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".md") }
                    .map { borg.trikeshed.forge.concept.DocSource(docsDir.fileName.toString() + "/" + it.fileName, Files.readString(it)) }
                    .toList()
            }.sortedBy { it.path }
        }.getOrElse {
            System.err.println("forge-bake: no docs corpus at $docsDir (${it.message}); the docs mindmap will be absent")
            emptyList()
        }
        System.err.println(
            "forge-bake: docs mindmap = ${docs.size} documents, " +
                "${borg.trikeshed.forge.concept.DocsGraph.edgesOf(docs).size} cross-references, " +
                "${borg.trikeshed.forge.concept.DocsGraph.orphans(docs).size} unlinked"
        )

        val html = ForgeApp.renderHtml(userId, bundles = bundles, docs = docs)
        val seedStart = html.indexOf("id=\"forge-seed\"")
        require(seedStart >= 0) { "rendered shell has no forge-seed slot" }
        val seedEmpty = html.regionMatches(html.indexOf('>', seedStart) + 1, "{}<", 0, 3)
        require(!seedEmpty) { "rendered shell has an empty seed; refusing to overwrite $out" }

        // A door to the operator surfaces. Publishing them without a link from the front page left
        // the site advertising one application while serving ten others nobody could find.
        val linked = html.replace(
            "</body>",
            """<a href="./surfaces.html" style="position:fixed;right:12px;bottom:12px;z-index:9999;""" +
                """font:600 13px/1 ui-monospace,SFMono-Regular,Menlo,monospace;padding:9px 13px;border-radius:6px;""" +
                """background:#f2a011;color:#12141a;text-decoration:none;box-shadow:0 2px 10px rgba(0,0,0,.28)">""" +
                """operator surfaces &rarr;</a></body>""",
        )
        require(linked !== html || "surfaces.html" in html) { "rendered shell has no </body> to hang the surfaces link on" }

        out.parent?.let { Files.createDirectories(it) }
        val tmp = Files.createTempFile(out.toAbsolutePath().parent, "index-", ".html")
        Files.writeString(tmp, linked)
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // createTempFile defaults to 0600; the page is published, so restore a normal mode.
        runCatching { Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rw-r--r--")) }
        System.err.println("forge-bake: wrote $out (${linked.length} chars; bundles=${bundles.ifEmpty { listOf("none") }})")

        writeSurfaceIndex(docsDir)
    }

    /**
     * The operator surfaces the daemon serves, as a page of their own.
     *
     * `generateForgePages` has always copied `web/` into `docs/`, so the surfaces were published --
     * and unreachable, because the baked `index.html` is ForgeApp and links to none of them. A
     * visitor to the site saw a different application from the one the daemon runs, which
     * `doc/moat-inventory.md` records as the gap. This lists what is actually on disk beside it,
     * so the page cannot advertise a surface the bake did not publish.
     */
    private fun writeSurfaceIndex(docsDir: Path) {
        val known = listOf(
            "harness.html" to "The LCNC harness: the node palette, presets, and a program's run receipts.",
            "panels.html" to "Patch panels: cables between typed ports, laid out on one canvas.",
            "kanban.html" to "The board. Cards claim themselves, a coding agent works them in a scratch clone, the plane judges the result.",
            "documents.html" to "Mounted project documents, rendered from commonMain.",
            "graal.html" to "The Graal console: VM worlds, pointcut facts, the terrain view.",
            "hermes-xterm.html" to "Hermes on a no-native GraalPy sleeve, on an xterm-256color panel with a real cursor.",
            "vm-terminal.html" to "One terminal tab per supervised VM or process.",
            "mux.html" to "ModelMux and KeyMux: which models are reachable, and on whose key.",
            "futon.html" to "The Couch 1.6 surface over the daemon's own store.",
        ).filter { Files.isRegularFile(docsDir.resolve(it.first)) }

        val rows = known.joinToString("\n") { (file, blurb) ->
            """    <li><a href="./$file">${file.removeSuffix(".html")}</a><span>${escape(blurb)}</span></li>"""
        }
        val page = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Operator surfaces &middot; TrikeShed</title>
<style>
:root{color-scheme:light dark;--ink:#16181d;--dim:#5d6472;--line:#d7dbe2;--bg:#fbfbfd;--accent:#b3610a}
@media(prefers-color-scheme:dark){:root{--ink:#dfe3ea;--dim:#8b93a4;--line:#2a303c;--bg:#0d0f14;--accent:#f2a011}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);
 font:16px/1.55 ui-sans-serif,-apple-system,Segoe UI,Roboto,sans-serif;padding:2.5rem 1.25rem}
main{max-width:44rem;margin:0 auto}h1{font-size:1.5rem;margin:0 0 .35rem}
p.lede{color:var(--dim);margin:0 0 1.5rem}
.note{border-left:3px solid var(--accent);background:color-mix(in srgb,var(--accent) 8%,transparent);
 padding:.7rem .9rem;border-radius:0 4px 4px 0;margin:0 0 1.75rem;font-size:.9rem}
ul{list-style:none;padding:0;margin:0}
li{border-top:1px solid var(--line);padding:.8rem 0;display:grid;grid-template-columns:11rem 1fr;gap:1rem;align-items:baseline}
li:last-child{border-bottom:1px solid var(--line)}
a{color:var(--accent);text-decoration:none;font-weight:600;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
a:hover{text-decoration:underline}span{color:var(--dim);font-size:.92rem}
code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.88em}
footer{margin-top:2rem;color:var(--dim);font-size:.85rem}
@media(max-width:34rem){li{grid-template-columns:1fr;gap:.2rem}}
</style></head><body><main>
<h1>Operator surfaces</h1>
<p class="lede">The pages the Oroboros daemon serves &mdash; the same files, published here.</p>
<div class="note"><strong>These are shells.</strong> Every surface reads its data from the daemon's
API, so opened as static files they render their layout and no content. To see them live:
<code>bin/oroboros-up --fresh --port 8899</code>, then <code>http://127.0.0.1:8899/</code>.</div>
<ul>
$rows
</ul>
<footer><a href="./index.html">&larr; Forge</a></footer>
</main></body></html>
"""
        val target = docsDir.resolve("surfaces.html")
        Files.writeString(target, page)
        runCatching { Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--")) }
        System.err.println("forge-bake: wrote $target (${known.size} surfaces published)")
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
