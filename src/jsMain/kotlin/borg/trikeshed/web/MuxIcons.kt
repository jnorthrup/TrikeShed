package borg.trikeshed.web

import kotlinx.browser.window

/** lucide 0.468.0 (was vendor/lucide-mux.js): the renderer and the icon nodes the pages use. */
@JsModule("lucide")
@JsNonModule
external object Lucide {
    fun createIcons(options: dynamic)
    val Workflow: dynamic
    val KeyRound: dynamic
    val Network: dynamic
    val MessagesSquare: dynamic
    val ChartNoAxesCombined: dynamic
    val Plus: dynamic
    val PanelsTopLeft: dynamic
    val Pause: dynamic
    val Play: dynamic
    val RefreshCw: dynamic
    val ArrowUp: dynamic
    val ArrowUpRight: dynamic
    val GitBranch: dynamic
    val Download: dynamic
    val Check: dynamic
    val X: dynamic
    val Square: dynamic
    val Archive: dynamic
    val Pencil: dynamic
    val Search: dynamic
    val Circle: dynamic
    val Clock: dynamic
    val ChevronRight: dynamic
    val GitMerge: dynamic
    val Copy: dynamic
    val RotateCcw: dynamic
    val ExternalLink: dynamic
    val Trash2: dynamic
    val MessageSquare: dynamic
}

/** `window.MuxIcons()`: replace every `[data-lucide]` element with its SVG from the fixed icon set. */
object MuxIcons {
    private val icons: dynamic by lazy {
        val o: dynamic = js("({})")
        o.Workflow = Lucide.Workflow; o.KeyRound = Lucide.KeyRound; o.Network = Lucide.Network
        o.MessagesSquare = Lucide.MessagesSquare; o.ChartNoAxesCombined = Lucide.ChartNoAxesCombined; o.Plus = Lucide.Plus
        o.PanelsTopLeft = Lucide.PanelsTopLeft; o.Pause = Lucide.Pause; o.Play = Lucide.Play; o.RefreshCw = Lucide.RefreshCw
        o.ArrowUp = Lucide.ArrowUp; o.ArrowUpRight = Lucide.ArrowUpRight; o.GitBranch = Lucide.GitBranch; o.Download = Lucide.Download
        o.Check = Lucide.Check; o.X = Lucide.X; o.Square = Lucide.Square; o.Archive = Lucide.Archive; o.Pencil = Lucide.Pencil
        o.Search = Lucide.Search; o.Circle = Lucide.Circle; o.Clock = Lucide.Clock; o.ChevronRight = Lucide.ChevronRight
        o.GitMerge = Lucide.GitMerge; o.Copy = Lucide.Copy; o.RotateCcw = Lucide.RotateCcw; o.ExternalLink = Lucide.ExternalLink
        o.Trash2 = Lucide.Trash2; o.MessageSquare = Lucide.MessageSquare
        o
    }

    /** Render the icons now. */
    fun render() {
        val options: dynamic = js("({})")
        options.icons = icons
        Lucide.createIcons(options)
    }

    /** Publish `window.MuxIcons` so markup-driven callers and [AppNav] find it. */
    fun install() {
        window.asDynamic().MuxIcons = { render() }
    }
}
