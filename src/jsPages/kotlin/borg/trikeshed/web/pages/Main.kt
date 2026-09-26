package borg.trikeshed.web.pages

import borg.trikeshed.web.AppNav
import borg.trikeshed.web.MuxIcons
import kotlinx.browser.document

/**
 * Entry point of the `pages` Kotlin/JS app: kanban, futon, hermes-xterm, vm-terminal, headhunter and mux
 * share this bundle; the page is picked by `body[data-app-page]` (vm-terminal, which carries none, by `#tabs`).
 */
fun main() {
    when (document.body?.getAttribute("data-app-page")) {
        "kanban" -> { chrome(); KanbanPage.mount() }
        "futon" -> { chrome(); FutonPage.mount() }
        "hermes" -> { chrome(); HermesTerminalPage.mount() }
        "headhunter" -> { chrome(); HeadhunterPage.mount() }
        "mux" -> { chrome(); MuxPage.mount() }
        else -> if (document.getElementById("tabs") != null && document.getElementById("command") != null) VmTerminalPage.mount()
    }
}

private fun chrome() {
    MuxIcons.install()
    AppNav.mount()
}
