package borg.trikeshed.graal.subvm

import borg.trikeshed.lcnc.CamelLinkage
import java.io.File
import java.util.zip.ZipFile

/**
 * CamelCatalog — what the mounted `camel` guest module can actually offer, read out of
 * its own jars.
 *
 * Camel describes itself in machine-readable form, and ships that description inside the
 * artifacts: every EIP is a JSON document at `META-INF/org/apache/camel/model/<name>.json`
 * in `camel-core-model`, and every component announces its scheme with a service file at
 * `META-INF/services/org/apache/camel/component/<scheme>`. Camel's own build generates the
 * YAML DSL, the XML schema and the tooling catalog from that same source, so reading it
 * here is not reverse engineering — it is the fourth consumer of a published contract.
 *
 * LAZY, in both senses the palette needs. Nothing is read until something asks: the
 * daemon boots without touching these 58 jars. And what is read is the CHEAP half —
 * [schemes] and [eips] enumerate zip entry NAMES, no parsing and no inflation — while the
 * expensive half ([eipJson], [componentJson], 71KB for `file` alone) is fetched per name
 * on demand. That is what lets one palette entry stand for a hundred without the palette
 * carrying a hundred dead rows.
 *
 * READ-ONLY BY CONSTRUCTION. This object opens zips. It never builds a URLClassLoader and
 * never resolves a class, so unlike a mount it cannot execute guest code — which is why it
 * is safe to call from a picklist that fills on every editor open. Mounting stays with
 * [GuestModules], which is the only thing here that can hand out an executable classpath.
 */
object CamelCatalog {

    /** The guest module these jars come from. */
    const val MODULE = "camel"

    private val EIP_ENTRY = Regex("""^META-INF/org/apache/camel/model/([A-Za-z0-9]+)\.json$""")
    private val COMPONENT_SERVICE = Regex("""^META-INF/services/org/apache/camel/component/([a-z0-9-]+)$""")

    /**
     * Cache fingerprint: the jar set as (name, size, mtime). A re-resolve of the module
     * changes it and the next call re-reads. Caching on the module NAME alone would serve
     * a stale catalog out of a classpath that had been replaced underneath it.
     */
    private data class Snapshot(val fingerprint: String, val eips: List<String>, val schemes: List<String>)

    @Volatile private var snapshot: Snapshot? = null

    /**
     * The jars a mount of [module] actually sees: its own, then everything it extends.
     * Enumerating only the module's own jars would report a department as having an engine
     * it does not ship and no EIPs at all, which is the opposite of what a mount resolves.
     */
    private fun jars(module: String): List<File> =
        GuestModules.chain(module).flatMap { GuestModules.jars(it) }

    private fun fingerprintOf(jars: List<File>): String =
        jars.sortedBy { it.name }.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }

    private fun read(module: String): Snapshot {
        val jars = jars(module)
        val fingerprint = fingerprintOf(jars)
        snapshot?.let { if (it.fingerprint == fingerprint) return it }
        val eips = sortedSetOf<String>()
        val schemes = sortedSetOf<String>()
        for (jar in jars) {
            // A jar that vanished between listing and opening is drift, not a failure: the
            // catalog reports what it can read rather than refusing to describe the module.
            runCatching {
                ZipFile(jar).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        EIP_ENTRY.matchEntire(name)?.let { eips += it.groupValues[1] }
                        COMPONENT_SERVICE.matchEntire(name)?.let { schemes += it.groupValues[1] }
                    }
                }
            }
        }
        val fresh = Snapshot(fingerprint, eips.toList(), schemes.toList())
        snapshot = fresh
        return fresh
    }

    /** True when the module is installed and there is something to enumerate. */
    fun available(module: String = MODULE): Boolean = GuestModules.isInstalled(module)

    /** Installed modules that extend another — the departments, spine excluded. */
    fun departments(): List<String> =
        GuestModules.installed().filter { GuestModules.manifest(it).parent != null }.sorted()

    /** The mount chain for [module], child first: what [CamelLinkage] should be told is mounted. */
    fun mounted(module: String = MODULE): List<String> = GuestModules.chain(module)

    /** Every EIP the mounted model jar declares, by name. Empty when the module is absent. */
    fun eips(module: String = MODULE): List<String> = read(module).eips

    /** Every component scheme the mounted jars announce. Empty when the module is absent. */
    fun schemes(module: String = MODULE): List<String> = read(module).schemes

    /** The full model JSON for one EIP, or null when the module or the name is absent. */
    fun eipJson(name: String, module: String = MODULE): String? =
        entryText("META-INF/org/apache/camel/model/$name.json", module)

    /** The full component JSON for one scheme, or null when the module or the scheme is absent. */
    fun componentJson(scheme: String, module: String = MODULE): String? =
        entryText("META-INF/org/apache/camel/component/$scheme/$scheme.json", module)

    private fun entryText(path: String, module: String): String? {
        for (jar in jars(module)) {
            val text = runCatching {
                ZipFile(jar).use { zip -> zip.getEntry(path)?.let { e -> zip.getInputStream(e).readBytes().decodeToString() } }
            }.getOrNull()
            if (text != null) return text
        }
        return null
    }

    /**
     * Endpoint exemplars for the palette picklist — one ready-to-run URI per scheme that
     * [CamelLinkage] admits, in the order an operator meets them.
     *
     * Exemplars rather than bare schemes because `from` and `to` take a URI, and a picklist
     * that fills the field with `seda` produces a route that will not build. A scheme with
     * no shaped example gets `<scheme>:lcnc`, which is the form most of them take.
     */
    fun endpoints(
        module: String = MODULE,
        reach: CamelLinkage.Reach = CamelLinkage.Reach.LOCAL,
    ): List<String> {
        val shaped = mapOf(
            "timer" to "timer:lcnc?period=1000",
            "scheduler" to "scheduler:lcnc?delay=1000",
            "file" to "file:.lcnc/in?noop=true",
            "language" to "language:simple:\${body}",
            "controlbus" to "controlbus:route?routeId=current&action=status",
            "dataset-test" to "dataset-test:lcnc",
            "imap" to "imaps://mail.example.com?username=USER&password=RAW(PASS)&delete=false",
            "imaps" to "imaps://mail.example.com?username=USER&password=RAW(PASS)&delete=false",
            "pop3" to "pop3://mail.example.com?username=USER&password=RAW(PASS)",
            "pop3s" to "pop3s://mail.example.com?username=USER&password=RAW(PASS)",
            "smtp" to "smtp://mail.example.com?username=USER&password=RAW(PASS)&to=TO",
            "smtps" to "smtps://mail.example.com?username=USER&password=RAW(PASS)&to=TO",
        )
        val chain = mounted(module)
        return schemes(module)
            .filter { CamelLinkage.admits("$it:x", reach, chain) }
            .map { shaped[it] ?: "$it:lcnc" }
    }

    /** Schemes on disk that [CamelLinkage] has no row for — drift between the mount and the table. */
    fun unlisted(module: String = MODULE): List<String> = CamelLinkage.unlisted(schemes(module))
}
