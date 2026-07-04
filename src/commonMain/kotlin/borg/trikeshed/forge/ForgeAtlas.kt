package borg.trikeshed.forge

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kanban.CardPriority
import borg.trikeshed.kanban.ForgeBoardFSM
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanCard
import borg.trikeshed.kanban.KanbanColumn
import borg.trikeshed.kanban.KanbanColumnId
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.KanbanCardId
import borg.trikeshed.kanban.cardsInColumn
import borg.trikeshed.kanban.moveCard
import kotlin.math.max

fun forgeAtlasHtml(): String {
    if (ForgeBoardFSM.current().activeBoard == null) {
        ForgeBoardFSM.loadDefault()
    }
    val board = ForgeBoardFSM.current().activeBoard ?: defaultBoard()
    val blackboard = ConfixBlackboard.fromMap(
        mapOf(
            "root" to "src/",
            "surface" to "notion-like atlas",
            "transport" to "nodejs js target",
            "metaphor" to "blackboard terrain",
        ),
        language = "forge-atlas",
    )
    val sections = listOf(
        AtlasSection("outline", "Outline", "Document-first launcher, linked sections, and live routes into the gallery."),
        AtlasSection("board", "Kanban terrain", "A working board with depth, shadow, and the familiar Notion-like workflow."),
        AtlasSection("radar", "Radar graph", "Zoom, pan, and flip across linked surface nodes instead of a flat list."),
        AtlasSection("blackboard", "Blackboard", "A content-addressed note surface that acts like a live development document."),
    )
    return buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("  <meta charset=\"utf-8\" />")
        appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />")
        appendLine("  <title>Forge — development atlas</title>")
        appendLine("  <style>")
        appendLine("    :root { --bg:#0b0f14; --panel:#111821; --panel2:#161e28; --ink:#d3dbe6; --muted:#758395; --border:#1d2a38; --accent:#7aa2f7; --success:#73daca; --warning:#e0af68; --danger:#f7768e; }")
        appendLine("    * { box-sizing:border-box; }")
        appendLine("    body { margin:0; background:var(--bg); color:var(--ink); font-family:Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }")
        appendLine("    .shell { min-height:100vh; padding:24px; background: radial-gradient(circle at top, rgba(122,162,247,.12), transparent 55%), var(--bg); }")
        appendLine("    .frame { max-width:1440px; margin:0 auto; border:1px solid var(--border); border-radius:28px; overflow:hidden; background:rgba(17,24,33,.92); box-shadow:0 24px 60px rgba(0,0,0,.35); }")
        appendLine("    .hero { padding:28px; display:grid; grid-template-columns: 1.3fr .9fr; gap:18px; border-bottom:1px solid var(--border); }")
        appendLine("    h1,h2,h3,p { margin:0; }")
        appendLine("    .eyebrow { text-transform:uppercase; letter-spacing:.18em; font-size:11px; color:var(--muted); }")
        appendLine("    .lede { margin-top:10px; color:var(--muted); line-height:1.55; }")
        appendLine("    .pill-row { display:flex; flex-wrap:wrap; gap:10px; margin-top:14px; }")
        appendLine("    .pill { padding:8px 12px; border-radius:999px; border:1px solid var(--border); background:rgba(15,20,27,.82); box-shadow:0 8px 22px rgba(0,0,0,.24); }")
        appendLine("    .layout { display:grid; grid-template-columns: 260px minmax(0, 1fr) 340px; gap:18px; padding:20px; }")
        appendLine("    .panel { background:linear-gradient(180deg, rgba(17,24,33,.96), rgba(17,24,33,.82)); border:1px solid var(--border); border-radius:24px; box-shadow:0 18px 34px rgba(0,0,0,.28), inset 0 1px 0 rgba(255,255,255,.04); }")
        appendLine("    .panel .inner { padding:18px; }")
        appendLine("    .outline-item, .board-card, .note-card { position:relative; overflow:hidden; border:1px solid var(--border); border-radius:20px; background:linear-gradient(180deg, rgba(17,24,33,.96), rgba(17,24,33,.82)); box-shadow:0 18px 34px rgba(0,0,0,.28), inset 0 1px 0 rgba(255,255,255,.04); }")
        appendLine("    .outline-item::before, .board-card::before, .note-card::before { content:''; position:absolute; inset:0; background:linear-gradient(145deg, color-mix(in srgb, var(--accent) 16%, transparent), transparent 38%, rgba(255,255,255,.05)); pointer-events:none; }")
        appendLine("    .outline-item > *, .board-card > *, .note-card > * { position:relative; z-index:1; }")
        appendLine("    .outline-item { padding:14px; margin-top:12px; }")
        appendLine("    .outline-item.active { border-color:var(--accent); box-shadow:0 22px 42px rgba(0,0,0,.34), inset 0 0 0 1px rgba(255,255,255,.06); }")
        appendLine("    .kicker { text-transform:uppercase; letter-spacing:.16em; font-size:10px; color:var(--muted); }")
        appendLine("    .card-grid { display:grid; gap:14px; grid-template-columns: repeat(2, minmax(0, 1fr)); }")
        appendLine("    .board-card, .note-card { padding:18px; }")
        appendLine("    .card-title { font-size:18px; font-weight:700; margin-top:6px; }")
        appendLine("    .card-value { color:var(--accent); font-size:12px; text-transform:uppercase; letter-spacing:.08em; margin-top:8px; }")
        appendLine("    .card-body { margin-top:12px; color:var(--muted); line-height:1.55; font-size:14px; }")
        appendLine("    .badge-row { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }")
        appendLine("    .badge { padding:7px 10px; border-radius:999px; border:1px solid var(--border); background:rgba(15,20,27,.82); color:var(--muted); }")
        appendLine("    .board-columns { display:grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap:12px; margin-top:12px; }")
        appendLine("    .column { padding:14px; border-radius:18px; border:1px solid var(--border); background:rgba(10, 14, 20, .55); }")
        appendLine("    .column h3 { font-size:14px; margin-bottom:10px; }")
        appendLine("    .column .card { margin-top:10px; padding:10px 12px; border-radius:14px; border:1px solid rgba(122,162,247,.12); background:rgba(15,20,27,.82); box-shadow:0 8px 22px rgba(0,0,0,.24); }")
        appendLine("    .route { margin-top:10px; padding:10px 12px; border-radius:14px; border:1px solid rgba(122,162,247,.16); background:rgba(122,162,247,.10); color:var(--ink); }")
        appendLine("    .route strong { color:var(--accent); }")
        appendLine("    .muted { color:var(--muted); }")
        appendLine("    .note-card + .note-card { margin-top:12px; }")
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <main class=\"shell\">")
        appendLine("    <div class=\"frame\">")
        appendLine("      <section class=\"hero\">")
        appendLine("        <div>")
        appendLine("          <p class=\"eyebrow\">Forge UI / Notion-like atlas</p>")
        appendLine("          <h1>Development atlas</h1>")
        appendLine("          <p class=\"lede\">Forge should look and move like Notion: document-first, linked, keyboard-friendly, and alive on the Node.js target.</p>")
        appendLine("          <div class=\"pill-row\">")
        appendLine("            <span class=\"pill\">document</span><span class=\"pill\">linked outline</span><span class=\"pill\">board</span><span class=\"pill\">radar</span><span class=\"pill\">nodejs</span>")
        appendLine("          </div>")
        appendLine("        </div>")
        appendLine("        <div>")
        appendLine("          <h2>Live routes</h2>")
        appendLine("          <p class=\"route\"><strong>/events</strong> SSE stream from the reactor</p>")
        appendLine("          <p class=\"route\"><strong>/taxonomy?topic=blackboard</strong> injects a linked node</p>")
        appendLine("          <p class=\"route\"><strong>jsNodeProductionRun</strong> prints this atlas on Node.js</p>")
        appendLine("        </div>")
        appendLine("      </section>")
        appendLine("      <section class=\"layout\">")
        appendLine("        <aside class=\"panel\"><div class=\"inner\">")
        appendLine("          <p class=\"eyebrow\">Outline</p>")
        for ((index, section) in sections.withIndex()) {
            val active = if (index == 0) " active" else ""
            appendLine("          <div class=\"outline-item$active\" style=\"--accent:${section.accent}\">")
            appendLine("            <div class=\"kicker\">${section.id}</div>")
            appendLine("            <div class=\"card-title\">${section.title}</div>")
            appendLine("            <div class=\"card-body\">${section.body}</div>")
            appendLine("          </div>")
        }
        appendLine("        </div></aside>")
        appendLine("        <section class=\"panel\"><div class=\"inner\">")
        appendLine("          <p class=\"eyebrow\">Board + blackboard</p>")
        appendLine("          <div class=\"card-grid\">")
        appendLine(renderBoardSummary(board))
        appendLine(renderBlackboardSummary(blackboard))
        appendLine("          </div>")
        appendLine("          <div class=\"board-columns\">")
        for (column in board.columns.sortedBy { it.order }) {
            appendLine("            <div class=\"column\">")
            appendLine("              <h3>${column.name}</h3>")
            val cards = board.cardsInColumn(column.id)
            if (cards.isEmpty()) {
                appendLine("              <div class=\"muted\">No cards</div>")
            } else {
                for (card in cards) {
                    appendLine("              <div class=\"card\">")
                    appendLine("                <div class=\"kicker\">${card.priority.name.lowercase()}</div>")
                    appendLine("                <div class=\"card-title\">${card.title}</div>")
                    if (card.description.isNotBlank()) appendLine("                <div class=\"muted\">${card.description}</div>")
                    appendLine("              </div>")
                }
            }
            appendLine("            </div>")
        }
        appendLine("          </div>")
        appendLine("        </div></section>")
        appendLine("        <aside class=\"panel\"><div class=\"inner\">")
        appendLine("          <p class=\"eyebrow\">Transitions</p>")
        appendLine(renderTransitionNotes())
        appendLine("        </div></aside>")
        appendLine("      </section>")
        appendLine("    </div>")
        appendLine("  </main>")
        appendLine("</body>")
        appendLine("</html>")
    }
}

private fun renderBoardSummary(board: KanbanBoard): String = buildString {
    appendLine("<article class=\"board-card\" style=\"--accent:#73daca\">")
    appendLine("  <div class=\"kicker\">kanban</div>")
    appendLine("  <div class=\"card-title\">${board.name}</div>")
    appendLine("  <div class=\"card-value\">${board.columns.size} columns · ${board.cards.size} cards</div>")
    appendLine("  <div class=\"card-body\">This board is the active workspace. It should feel like a living Notion database — but with direct motion, drag states, and clear command surfaces.</div>")
    appendLine("  <div class=\"badge-row\">")
    appendLine("    <span class=\"badge\">shadow</span><span class=\"badge\">reflection</span><span class=\"badge\">scale</span>")
    appendLine("  </div>")
    appendLine("</article>")
}

private fun renderBlackboardSummary(blackboard: ConfixBlackboard): String = buildString {
    appendLine("<article class=\"note-card\" style=\"--accent:#bb9af7\">")
    appendLine("  <div class=\"kicker\">blackboard</div>")
    appendLine("  <div class=\"card-title\">Content-addressed notes</div>")
    appendLine("  <div class=\"card-value\">${blackboard.keys().size} entries</div>")
    appendLine("  <div class=\"card-body\">A blackboard surface that carries transition metadata: root, surface, transport, and metaphor. The document can navigate, not just describe.</div>")
    appendLine("  <div class=\"badge-row\">")
    for (key in blackboard.keys()) {
        appendLine("    <span class=\"badge\">$key</span>")
    }
    appendLine("  </div>")
    appendLine("</article>")
}

private fun renderTransitionNotes(): String = buildString {
    val lines = listOf(
        "Document-first layout should open like Notion, not a settings panel.",
        "The blackboard should be a linked scope, not a dead note list.",
        "The Node.js target must print an atlas that matches the live app's mental model.",
        "Radar is the map; board is the work; outline is the door.",
    )
    for (line in lines) {
        appendLine("<div class=\"route\">$line</div>")
    }
}

private fun defaultBoard(): KanbanBoard {
    val backlog = KanbanColumnId("col-backlog")
    val inprog = KanbanColumnId("col-inprogress")
    val review = KanbanColumnId("col-review")
    val done = KanbanColumnId("col-done")
    return KanbanBoard(
        id = KanbanBoardId("board-default"),
        name = "Forge Board",
        columns = listOf(
            KanbanColumn(backlog, "Backlog", 0),
            KanbanColumn(inprog, "In Progress", 1, wipLimit = 3),
            KanbanColumn(review, "Review", 2),
            KanbanColumn(done, "Done", 3),
        ),
        cards = listOf(
            KanbanCard(KanbanCardId("c1"), "Setup CI pipeline", columnId = backlog, priority = CardPriority.HIGH),
            KanbanCard(KanbanCardId("c2"), "Add user authentication", columnId = backlog),
            KanbanCard(KanbanCardId("c3"), "Implement API gateway", columnId = inprog, priority = CardPriority.CRITICAL),
            KanbanCard(KanbanCardId("c4"), "Code review: HTX client", columnId = review, priority = CardPriority.HIGH),
            KanbanCard(KanbanCardId("c5"), "Initial commit", columnId = done, priority = CardPriority.LOW),
            KanbanCard(KanbanCardId("c6"), "Fix login bug", columnId = backlog, priority = CardPriority.HIGH),
        ),
    )
}

private data class AtlasSection(
    val id: String,
    val title: String,
    val body: String,
    val accent: String = "#7aa2f7",
)
