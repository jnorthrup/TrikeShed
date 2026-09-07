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

        out.parent?.let { Files.createDirectories(it) }
        val tmp = Files.createTempFile(out.toAbsolutePath().parent, "index-", ".html")
        Files.writeString(tmp, html)
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // createTempFile defaults to 0600; the page is published, so restore a normal mode.
        runCatching { Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rw-r--r--")) }
        System.err.println("forge-bake: wrote $out (${html.length} chars; bundles=${bundles.ifEmpty { listOf("none") }})")
    }
}
