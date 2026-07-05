package borg.trikeshed.forge

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.kanban.ForgeBoardFSM
import borg.trikeshed.kanban.KanbanBoard
import borg.trikeshed.kanban.KanbanBoardId
import borg.trikeshed.kanban.cardsInColumn
import borg.trikeshed.kanban.toMermaidCausal

data class ForgeAtlasRoute(
    val path: String,
    val description: String,
)

data class ForgeAtlasSection(
    val id: String,
    val title: String,
    val body: String,
    val accent: String = "#7aa2f7",
)

data class ForgeAtlasSnapshot(
    val board: KanbanBoard,
    val boardLoaded: Boolean,
    val activeBoardId: String?,
    val lastEventKind: String,
    val lastEventMs: Long,
    val blackboard: ConfixBlackboard,
    val routes: List<ForgeAtlasRoute>,
)

fun forgeAtlasSnapshot(): ForgeAtlasSnapshot {
    if (ForgeBoardFSM.current().activeBoard == null) {
        ForgeBoardFSM.loadDefault()
    }
    val state = ForgeBoardFSM.current()
    val board = state.activeBoard ?: emptyBoard()
    val boardLoaded = state.activeBoard != null
    val blackboard = ConfixBlackboard.fromMap(
        mapOf(
            "root" to "src/",
            "surface" to "forge proof surface",
            "runtime.board.loaded" to boardLoaded,
            "runtime.board.id" to (state.activeBoardId?.value ?: "none"),
            "runtime.lastEventKind" to state.lastEventKind,
            "runtime.lastEventMs" to state.lastEventMs,
            "transport" to "root kmpp js browser/node target",
            "autonomy" to "local mesh confix blackboard",
            "board.name" to board.name,
            "board.columns" to board.columns.size,
            "board.cards" to board.cards.size,
        ),
        language = "forge-atlas",
    )
    val routes = listOf(
        ForgeAtlasRoute("jsBrowserProductionWebpack", "build browser bundle"),
        ForgeAtlasRoute("generateForgePages", "publish browser bundle into docs/"),
        ForgeAtlasRoute("jsNodeProductionRun", "print the same atlas HTML on Node.js"),
    )
    return ForgeAtlasSnapshot(
        board = board,
        boardLoaded = boardLoaded,
        activeBoardId = state.activeBoardId?.value,
        lastEventKind = state.lastEventKind,
        lastEventMs = state.lastEventMs,
        blackboard = blackboard,
        routes = routes,
    )
}

fun forgeAtlasHtml(): String {
    val snapshot = forgeAtlasSnapshot()
    val board = snapshot.board
    val blackboard = snapshot.blackboard
    val boardGraph = board.toMermaidCausal().trim()
    return buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("  <meta charset=\"utf-8\" />")
        appendLine("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\" />")
        appendLine("  <title>TrikeShed Forge proof</title>")
        appendLine("  <style>")
        appendLine("    :root { --bg:#0b0f14; --panel:#111821; --ink:#d3dbe6; --muted:#758395; --border:#1d2a38; --accent:#7aa2f7; }")
        appendLine("    * { box-sizing:border-box; }")
        appendLine("    body { margin:0; background:var(--bg); color:var(--ink); font-family:Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; }")
        appendLine("    .shell { min-height:100vh; padding:24px; }")
        appendLine("    .frame { max-width:1440px; margin:0 auto; display:grid; gap:16px; }")
        appendLine("    .panel { border:1px solid var(--border); border-radius:16px; background:var(--panel); padding:16px; }")
        appendLine("    .facts { display:grid; grid-template-columns:repeat(auto-fit, minmax(160px, 1fr)); gap:12px; }")
        appendLine("    .fact { border:1px solid var(--border); border-radius:12px; padding:12px; }")
        appendLine("    .label { color:var(--muted); font-size:12px; text-transform:uppercase; letter-spacing:.08em; }")
        appendLine("    .value { margin-top:6px; font-size:20px; font-weight:700; }")
        appendLine("    .grid { display:grid; grid-template-columns:2fr 1fr; gap:16px; }")
        appendLine("    .columns { display:grid; grid-template-columns:repeat(auto-fit, minmax(220px, 1fr)); gap:12px; }")
        appendLine("    .column { border:1px solid var(--border); border-radius:12px; padding:12px; }")
        appendLine("    .card { border-top:1px solid var(--border); padding-top:8px; margin-top:8px; }")
        appendLine("    .muted { color:var(--muted); }")
        appendLine("    ul, pre { margin:0; }")
        appendLine("    li + li { margin-top:6px; }")
        appendLine("    table { width:100%; border-collapse:collapse; }")
        appendLine("    td { border-top:1px solid var(--border); padding:8px 0; vertical-align:top; }")
        appendLine("    td:first-child { color:var(--muted); width:40%; padding-right:12px; }")
        appendLine("    pre { overflow:auto; padding:12px; border:1px solid var(--border); border-radius:12px; background:#0a0f15; }")
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <main class=\"shell\">")
        appendLine("    <div class=\"frame\">")
        appendLine("      <section class=\"panel\">")
        appendLine("        <div class=\"label\">Forge proof</div>")
        appendLine("        <div class=\"value\">root KMPP JS target</div>")
        appendLine("        <div class=\"facts\">")
        appendLine(renderFact("board loaded", snapshot.boardLoaded.toString()))
        appendLine(renderFact("active board", snapshot.activeBoardId ?: "none"))
        appendLine(renderFact("columns", board.columns.size.toString()))
        appendLine(renderFact("cards", board.cards.size.toString()))
        appendLine(renderFact("last event", snapshot.lastEventKind))
        appendLine(renderFact("blackboard keys", blackboard.keys().size.toString()))
        appendLine("        </div>")
        appendLine("      </section>")
        appendLine("      <section class=\"grid\">")
        appendLine("        <section class=\"panel\">")
        appendLine("          <div class=\"label\">board columns</div>")
        appendLine("          <div class=\"columns\">")
        for (column in board.columns.sortedBy { it.order }) {
            appendLine("            <div class=\"column\">")
            appendLine("              <div class=\"value\">${column.name}</div>")
            appendLine("              <div class=\"muted\">order ${column.order} · ${board.cardsInColumn(column.id).size} cards</div>")
            val cards = board.cardsInColumn(column.id)
            if (cards.isEmpty()) {
                appendLine("              <div class=\"card muted\">No cards</div>")
            } else {
                for (card in cards) {
                    appendLine("              <div class=\"card\">")
                    appendLine("                <div class=\"label\">${card.priority.name.lowercase()}</div>")
                    appendLine("                <div>${card.title}</div>")
                    if (card.description.isNotBlank()) appendLine("                <div class=\"muted\">${card.description}</div>")
                    appendLine("              </div>")
                }
            }
            appendLine("            </div>")
        }
        appendLine("          </div>")
        appendLine("        </section>")
        appendLine("        <aside class=\"panel\">")
        appendLine("          <div class=\"label\">build routes</div>")
        appendLine("          <ul>")
        for (route in snapshot.routes) {
            appendLine("            <li><strong>${route.path}</strong> — ${route.description}</li>")
        }
        appendLine("          </ul>")
        appendLine("        </aside>")
        appendLine("      </section>")
        appendLine("      <section class=\"grid\">")
        appendLine("        <section class=\"panel\">")
        appendLine("          <div class=\"label\">blackboard</div>")
        appendLine(renderBlackboardTable(blackboard))
        appendLine("        </section>")
        appendLine("        <section class=\"panel\">")
        appendLine("          <div class=\"label\">board causal graph</div>")
        appendLine("          <pre>${boardGraph}</pre>")
        appendLine("        </section>")
        appendLine("      </section>")
        appendLine("    </div>")
        appendLine("  </main>")
        appendLine("</body>")
        appendLine("</html>")
    }
}

private fun renderFact(label: String, value: String): String = buildString {
    appendLine("<div class=\"fact\">")
    appendLine("  <div class=\"label\">$label</div>")
    appendLine("  <div class=\"value\">$value</div>")
    appendLine("</div>")
}

private fun renderBlackboardTable(blackboard: ConfixBlackboard): String = buildString {
    appendLine("<table>")
    for (key in blackboard.keys()) {
        appendLine("  <tr><td>$key</td><td>${blackboard.get(key)}</td></tr>")
    }
    appendLine("</table>")
}

private fun emptyBoard(): KanbanBoard {
    return KanbanBoard(
        id = KanbanBoardId("board-empty"),
        name = "No active board loaded",
        columns = emptyList(),
        cards = emptyList(),
    )
}
