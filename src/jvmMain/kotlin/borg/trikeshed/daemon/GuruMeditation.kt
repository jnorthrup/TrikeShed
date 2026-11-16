package borg.trikeshed.daemon

/**
 * Guru Meditation: what the daemon says when a class file crashes.
 *
 * A class-file crash is any [LinkageError] in the cause chain: a bad hotswap generation
 * (VerifyError, ClassFormatError, UnsupportedClassVersionError), a class that was removed
 * or never published (NoClassDefFoundError), caller and callee from different builds
 * (NoSuchMethodError, IncompatibleClassChangeError), or a static initializer that threw
 * (ExceptionInInitializerError).
 *
 * The report names the class loader responsible. The second half of the guru number is that
 * loader's identity hash, the way the Amiga printed the task address. The loader chain
 * records, for each loader up to the bootstrap, whether it can see the failing class file
 * and from which URL, so a crash from `build/live/classes` can be told apart from one in a jar snapshot.
 *
 * Self-contained on purpose (JDK types only): HotSwapAgent loads this from hotswap-agent.jar,
 * and a missing dependency there would be the same crash it is trying to report.
 */
object GuruMeditation {
    data class Loader(
        /** Runtime class of the loader, e.g. jdk.internal.loader.ClassLoaders$AppClassLoader. */
        val type: String,
        /** ClassLoader.getName(): "app", "platform", or null for an unnamed loader. */
        val name: String?,
        /** Identity hash, 8 hex digits. */
        val id: String,
        /** URL of the failing class file as this loader sees it; null when it cannot see it. */
        val sees: String?,
    )

    data class Report(
        /** Guru number, `#KKKKKKKK.LLLLLLLL`: error kind, then the responsible loader's identity. */
        val code: String,
        val kind: String,
        val message: String,
        val className: String?,
        /** Loader name recorded in the crashing stack frame, when the JVM provides one. */
        val frameLoader: String?,
        /** The nearest loader in the chain that can see the failing class file. */
        val loader: Loader?,
        val chain: List<Loader>,
        /** URL of the failing class file, when some loader in the chain can see it. */
        val origin: String?,
        val hint: String,
        val atMs: Long,
    )

    private const val RING = 16
    private val ring = ArrayDeque<Report>()

    /** The most recent reports, oldest first. */
    fun recent(): List<Report> = synchronized(ring) { ring.toList() }

    /** The first [LinkageError] in the cause chain, or null when this is not a class-file crash. */
    fun find(t: Throwable): Throwable? {
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 16) {
            if (cur is LinkageError) return cur
            val next = cur.cause
            cur = if (next === cur) null else next
            depth++
        }
        return null
    }

    fun isClassFileCrash(t: Throwable): Boolean = find(t) != null

    /**
     * Describe [t]. [target] is the class being redefined when the crash happened in the hotswap
     * agent; [context] is where to start the loader chain (default: the thread's context loader);
     * [named] names the class when the caller knows it and the JVM message does not (a truncated class file says nothing).
     */
    fun describe(t: Throwable, target: Class<*>? = null, context: ClassLoader? = null, named: String? = null): Report {
        val crash = find(t) ?: t
        val kind = crash.javaClass.simpleName.ifEmpty { crash.javaClass.name }
        val message = (crash.message ?: "").take(600)
        val start = context ?: target?.classLoader
            ?: Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()

        val candidates = LinkedHashSet<String>()
        target?.let { candidates += it.name }
        named?.let { candidates += it }
        for (m in NAME.findAll(message)) {
            val dotted = m.value.replace('/', '.')
            candidates += dotted
            if ('.' in dotted) candidates += dotted.substringBeforeLast('.')   // "pkg.Foo.method" -> "pkg.Foo"
        }
        // Stack frames only stand in when neither the caller nor the JVM message named the class
        // (ExceptionInInitializerError names it only through the failing <clinit> frame).
        if (candidates.none { '.' in it && !isJdk(it) }) {
            for (frame in crash.stackTrace.take(6)) if (!isJdk(frame.className)) candidates += frame.className
        }
        val className: String? = candidates.firstOrNull { '.' in it && !isJdk(it) } ?: target?.name
        var origin: String? = null
        val chain = ArrayList<Loader>()
        var responsible: Loader? = null
        var cl: ClassLoader? = start
        var hops = 0
        while (cl != null && hops < 16) {
            var sees: String? = null
            for (c in candidates) {
                val url = try { cl.getResource(c.replace('.', '/') + ".class") } catch (_: Throwable) { null }
                if (url != null) { sees = url.toString(); break }
            }
            val info = Loader(cl.javaClass.name, cl.name, hex(System.identityHashCode(cl)), sees)
            chain += info
            if (responsible == null && sees != null) { responsible = info; origin = sees }
            cl = cl.parent
            hops++
        }
        chain += Loader("bootstrap", "boot", "00000000", null)

        val frameLoader = crash.stackTrace.firstOrNull { it.classLoaderName != null }?.classLoaderName
        val owner = responsible ?: chain.first()
        val report = Report(
            code = "#" + hex(kindCode(crash)) + "." + owner.id,
            kind = kind, message = message, className = className, frameLoader = frameLoader,
            loader = responsible, chain = chain, origin = origin, hint = hint(crash),
            atMs = System.currentTimeMillis(),
        )
        synchronized(ring) { ring.addLast(report); while (ring.size > RING) ring.removeFirst() }
        return report
    }

    // ── renderings ──────────────────────────────────────────────────────────

    fun text(r: Report): String = buildString {
        val deadEnd = r.code.drop(1).take(1) in "89ABCDEF"
        append(if (deadEnd) "Software Failure.   Press left mouse button to continue.\n"
               else "Recoverable Alert.   Press left mouse button to continue.\n")
        append("Guru Meditation ").append(r.code).append("\n\n")
        append("  ").append(r.kind)
        if (r.message.isNotEmpty()) append(": ").append(r.message.lineSequence().first().take(200))
        append('\n')
        append("  class   ").append(r.className ?: "(the JVM did not name it)").append('\n')
        val l = r.loader
        if (l != null) {
            append("  loader  ").append(label(l)).append('\n')
        } else if (r.className == null) {
            append("  loader  ").append(label(r.chain.first())).append(" (context loader; the class is unnamed)\n")
        } else {
            append("  loader  no loader in the chain can see the class file\n")
        }
        r.frameLoader?.let { append("  frame   crashed in loader \"").append(it).append("\"\n") }
        r.origin?.let { append("  from    ").append(it).append('\n') }
        append("  chain   ").append(r.chain.joinToString(" -> ") { label(it) }).append('\n')
        append("  hint    ").append(r.hint).append('\n')
    }

    fun json(r: Report): String = buildString {
        append("{\"guru\":").append(q(r.code))
        append(",\"error\":\"class_file_crash\"")
        append(",\"kind\":").append(q(r.kind))
        append(",\"message\":").append(q(r.message))
        append(",\"class\":").append(r.className?.let(::q) ?: "null")
        append(",\"origin\":").append(r.origin?.let(::q) ?: "null")
        append(",\"frameLoader\":").append(r.frameLoader?.let(::q) ?: "null")
        append(",\"loader\":").append(r.loader?.let(::loaderJson) ?: "null")
        append(",\"chain\":[").append(r.chain.joinToString(",") { loaderJson(it) }).append(']')
        append(",\"hint\":").append(q(r.hint))
        append(",\"atMs\":").append(r.atMs)
        append('}')
    }

    fun html(r: Report): String {
        val body = esc(text(r))
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Guru Meditation ${esc(r.code)}</title>" +
            "<style>html,body{margin:0;height:100%;background:#000;color:#f00;font:16px/1.4 ui-monospace,Menlo,monospace}" +
            "pre{margin:6vh auto;max-width:92ch;padding:1em 1.4em;border:4px solid #f00;white-space:pre-wrap;word-break:break-all}" +
            "@media(prefers-reduced-motion:no-preference){pre{animation:guru 2s steps(1) infinite}}" +
            "@keyframes guru{50%{border-color:#000}}</style></head><body><pre>$body</pre></body></html>"
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_$]*(?:[./][A-Za-z_$][A-Za-z0-9_$]*)+")

    private fun isJdk(n: String) = n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.") ||
        n.startsWith("javax.") || n.startsWith("kotlin.") || n.startsWith("kotlinx.")

    private fun hex(v: Int): String = String.format("%08X", v)

    private fun kindCode(t: Throwable): Int = when (t) {
        is UnsupportedClassVersionError -> 0x81000002.toInt()
        is ClassFormatError -> 0x81000001.toInt()
        is VerifyError -> 0x81000003.toInt()
        is ClassCircularityError -> 0x81000005.toInt()
        is ExceptionInInitializerError -> 0x8100000C.toInt()
        is NoClassDefFoundError -> 0x00000004
        is NoSuchMethodError -> 0x00000007
        is NoSuchFieldError -> 0x00000008
        is AbstractMethodError -> 0x00000009
        is IncompatibleClassChangeError -> 0x0000000A
        is IllegalAccessError -> 0x0000000B
        else -> 0x0000000F
    }

    private fun hint(t: Throwable): String = when (t) {
        is VerifyError, is ClassFormatError, is UnsupportedClassVersionError, is ClassCircularityError ->
            "the class bytes are bad; the JVM kept the previous class. Fix the source, rebuild, publish the generation again (hotswapFeed), or restart"
        is ExceptionInInitializerError -> "a static initializer threw; see the cause chain. The class stays unusable until restart"
        is NoClassDefFoundError -> "a class was removed, never published, or failed to initialise earlier; restart to rebuild the class path"
        is NoSuchMethodError, is NoSuchFieldError, is AbstractMethodError, is IncompatibleClassChangeError, is IllegalAccessError ->
            "caller and callee come from different builds (a schema change hotswap cannot apply); restart"
        else -> "restart the daemon"
    }

    private fun label(l: Loader): String =
        (l.name ?: l.type.substringAfterLast('.')) + "@" + l.id

    private fun loaderJson(l: Loader): String =
        "{\"type\":" + q(l.type) + ",\"name\":" + (l.name?.let(::q) ?: "null") +
            ",\"id\":" + q(l.id) + ",\"sees\":" + (l.sees?.let(::q) ?: "null") + "}"

    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
