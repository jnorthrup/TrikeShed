package borg.trikeshed.web.pages

/** xterm-256color cell colours and signal-rail text for the Hermes and VM terminal pages. */
object TerminalPalette {
    val HERMES_ANSI16: List<String> = listOf(
        "#000000", "#cd3131", "#0dbc79", "#e5e510", "#2472c8", "#bc3fbc", "#11a8cd", "#e5e5e5",
        "#666666", "#f14c4c", "#23d18b", "#f5f543", "#3b8eea", "#d670d6", "#29b8db", "#ffffff",
    )
    val VM_ANSI16: List<String> = listOf(
        "#000", "#cd3131", "#0dbc79", "#e5e510", "#2472c8", "#bc3fbc", "#11a8cd", "#e5e5e5",
        "#666", "#f14c4c", "#23d18b", "#f5f543", "#3b8eea", "#d670d6", "#29b8db", "#fff",
    )

    /** Truecolour when [rgb] is set, else [fallback] for index < 0, the 16-colour table, the 6×6×6 cube, or the grey ramp. */
    fun color(ansi16: List<String>, index: Int, rgb: Long?, fallback: String): String {
        if (rgb != null) return "#" + rgb.toString(16).padStart(6, '0')
        if (index < 0) return fallback
        if (index < 16) return ansi16[index]
        if (index >= 232) {
            val v = 8 + (index - 232) * 10
            return "rgb($v,$v,$v)"
        }
        val n = index - 16
        val r = n / 36
        val g = n % 36 / 6
        val b = n % 6
        fun f(x: Int) = if (x != 0) 55 + x * 40 else 0
        return "rgb(${f(r)},${f(g)},${f(b)})"
    }

    /** Hermes rail escape: &, <, ". */
    fun escapeHermes(x: String): String = x.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /** VM rail escape: &, <. */
    fun escapeVm(x: String): String = x.replace("&", "&amp;").replace("<", "&lt;")

    /** Control characters (and DEL when [withDel]) folded to spaces, cut to [limit] UTF-16 units. */
    fun preview(payload: String, withDel: Boolean, limit: Int): String {
        val folded = buildString {
            for (c in payload) append(if (c.code <= 0x1f || (withDel && c.code == 0x7f)) ' ' else c)
        }
        return folded.take(limit)
    }

    /** Signal id shown in a rail row: the `sha256:` prefix dropped, cut to [limit]. */
    fun shortSignal(id: String, limit: Int): String = id.replaceFirst("sha256:", "").take(limit)
}
