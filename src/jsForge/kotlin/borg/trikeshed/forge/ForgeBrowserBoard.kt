@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

import borg.trikeshed.forge.board.boardMoveCommand
import borg.trikeshed.forge.board.boardSubmitCommand
import borg.trikeshed.forge.board.nextColumnId
import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Board render — the jsForge DOM half of script.js "Render: board". Card click is a real Move
 * command (optimistic UI; the flush verdict re-hydrates on rejection or sequence advance).
 */

fun ForgeBrowser.renderBoard() {
    val boardCanvasEl = el("board-canvas") ?: return
    boardCanvasEl.clearChildren()
    for (col in workspace.board.columns) {
        val colEl = el("div", "board-column")
        val head = el("div", "board-column-head")
        val cards = workspace.board.cards.filter { it.column == col.id }
        head.append(el("span", "", col.name), el("span", "board-column-count", cards.size.toString()))
        colEl.appendChild(head)

        val cardsEl = el("div", "board-cards")
        for (card in cards) {
            val cardEl = el("div", "board-card" + (if (col.id == "done") " done-card" else ""))
            cardEl.setAttribute("role", "button")
            cardEl.tabIndex = 0
            val columns = workspace.board.columns
            val nextColName = columns[(columns.indexOf(col) + 1) % columns.size].name
            cardEl.setAttribute("aria-label", card.title + " in " + col.name + ". Activate to move to " + nextColName)
            cardEl.appendChild(el("div", "board-card-title", card.title))
            if (card.meta.isNotEmpty()) cardEl.appendChild(el("div", "board-card-meta", card.meta))
            cardEl.addEventListener("click", {
                val next = workspace.board.nextColumnId(card.column) ?: return@addEventListener
                card.revision?.let { rev ->
                    queueBoardCommand(boardMoveCommand(card.id, rev, next))
                }
                mutate { card.column = next }
                renderBoard()
            })
            cardsEl.appendChild(cardEl)
        }
        colEl.appendChild(cardsEl)

        val addBtn = el("button", "board-add-card", "+ New")
        addBtn.setAttribute("aria-label", "Add new card to " + col.name)
        addBtn.addEventListener("click", {
            val title = (windowPrompt("Card title") ?: "").trim()
            if (title.isEmpty()) return@addEventListener
            val jobId = "card-" + forgeUid()
            queueBoardCommand(boardSubmitCommand(jobId, title))
            mutate { s ->
                s.board.cards.add(borg.trikeshed.forge.board.ForgeBoardCard(jobId, title, col.id, revision = 1, meta = ""))
            }
            renderBoard()
        })
        colEl.appendChild(addBtn)
        boardCanvasEl.appendChild(colEl)
    }
}

fun windowPrompt(message: String): String? = js("window.prompt(message)") as? String
