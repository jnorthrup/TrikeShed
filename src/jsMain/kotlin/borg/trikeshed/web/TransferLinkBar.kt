package borg.trikeshed.web

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify
import borg.trikeshed.transfers.*
import kotlinx.browser.window

/**
 * A page's links to the transfers asked for by what it shows: every [PERIOD_MS] it reads `/api/transfers` and lists in
 * [host] the rows whose context is ([kind], [id]()), each linking to `/transfers?transfer=<id>`. Nothing shows while
 * [id] gives null or no row matches.
 */
class TransferLinkBar(val host: dynamic, val kind: String, val id: () -> String?) {
    var timer = 0
    var stopped = false
    var shown = ""

    fun start() { stopped = false; read() }

    fun stop() { stopped = true; window.clearTimeout(timer) }

    fun read() {
        if (stopped) return
        val current = id()
        if (current == null) { show(""); timer = window.setTimeout({ read() }, PERIOD_MS); return }
        window.asDynamic().fetch("/api/transfers").then({ r: dynamic -> r.text() }).then({ body: dynamic ->
            val rows = ((reify(str(body)) as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()).map { TransferRow.of(it) }.toSeries()
            show(TransferLinks.matching(rows, kind, current).view.joinToString("") { r ->
                "<a class=\"transfer-link\" href=\"${escape(TransferLinks.transfer(r.id))}\" style=\"margin-left:8px\">transfer · ${escape(r.name.ifEmpty { r.id })} · ${escape(r.state)}</a>"
            })
            if (!stopped) timer = window.setTimeout({ read() }, PERIOD_MS)
        }).catch({ _: dynamic -> if (!stopped) timer = window.setTimeout({ read() }, PERIOD_MS) })
    }

    fun show(html: String) { if (html != shown) { shown = html; host.innerHTML = html } }

    companion object {
        const val PERIOD_MS = 5000
        fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }
}
