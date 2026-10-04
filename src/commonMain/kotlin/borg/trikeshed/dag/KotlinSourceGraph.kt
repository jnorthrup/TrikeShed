package borg.trikeshed.dag

import borg.trikeshed.lib.*

/** 32-bit FNV-1a over an identifier's UTF-8 bytes. */
fun identifierHash(src: ByteArray, from: Int, to: Int): Int {
    var h = -0x7ee3623b
    for (k in from until to) h = (h xor (src[k].toInt() and 0xff)) * 0x01000193
    return h
}

fun identifierHash(name: CharSequence): Int = borg.trikeshed.parse.utf8(name).let { identifierHash(it, 0, it.size) }

/**
 * Header facts of one Kotlin source: its `package`, its `import` paths (a wildcard keeps its
 * trailing `.*`), the names it declares at brace depth 0, and the sorted distinct hashes of every
 * identifier outside comments and string literals.
 */
class KotlinHeader(val pkg: String, val imports: List<String>, val declared: List<String>, val referenced: IntArray)

fun isIdentifierByte(b: Int): Boolean =
    b == '_'.code || b in 'a'.code..'z'.code || b in 'A'.code..'Z'.code || b in '0'.code..'9'.code || b >= 0x80

fun kotlinHeader(src: ByteArray): KotlinHeader {
    val n = src.size
    var pkg = ""
    val imports = ArrayList<String>()
    val declared = ArrayList<String>()
    var refs = IntArray(256)
    var rc = 0
    var depth = 0
    var parens = 0
    var i = 0
    fun at(k: Int): Int = if (k < n) src[k].toInt() and 0xff else 0
    fun skipBlank(k0: Int): Int { var k = k0; while (k < n && (at(k) == ' '.code || at(k) == '\t'.code || at(k) == '\n'.code || at(k) == '\r'.code)) k++; return k }
    fun skipGenerics(k0: Int): Int {
        if (at(k0) != '<'.code) return k0
        var d = 0; var k = k0
        while (k < n) { val c = at(k); if (c == '<'.code) d++ else if (c == '>'.code) { d--; if (d == 0) return k + 1 } else if (c == '{'.code || c == '('.code || c == '='.code) return k; k++ }
        return k
    }
    fun wordAt(k0: Int): Pair<Int, Int>? {
        if (at(k0) == '`'.code) { var k = k0 + 1; while (k < n && at(k) != '`'.code && at(k) != '\n'.code) k++; return (k0 + 1) to k }
        if (!isIdentifierByte(at(k0)) || at(k0) in '0'.code..'9'.code) return null
        var k = k0; while (k < n && isIdentifierByte(at(k))) k++
        return k0 to k
    }
    fun text(r: Pair<Int, Int>): String = src.decodeToString(r.first, r.second)
    /** `fun`/`val`/`var` name: last segment of the (generic-skipping) receiver chain. */
    fun callableName(k0: Int): String? {
        var k = skipGenerics(skipBlank(k0))
        k = skipBlank(k)
        var last: Pair<Int, Int>? = null
        while (true) {
            val w = wordAt(k) ?: break
            last = w
            k = if (at(w.first - 1) == '`'.code) w.second + 1 else w.second
            k = skipGenerics(k)
            if (at(k) == '?'.code) k++
            if (at(k) == '.'.code) k++ else break
        }
        return last?.let(::text)
    }
    fun path(k0: Int): Pair<String, Int> {
        var k = k0
        while (k < n && at(k) != '\n'.code && at(k) != ';'.code && (isIdentifierByte(at(k)) || at(k) == '.'.code || at(k) == '*'.code || at(k) == '`'.code)) k++
        return src.decodeToString(k0, k).replace("`", "") to k
    }
    while (i < n) {
        val c = at(i)
        when {
            c == '/'.code && at(i + 1) == '/'.code -> { while (i < n && at(i) != '\n'.code) i++ }
            c == '/'.code && at(i + 1) == '*'.code -> {
                var d = 1; i += 2
                while (i < n && d > 0) { if (at(i) == '/'.code && at(i + 1) == '*'.code) { d++; i += 2 } else if (at(i) == '*'.code && at(i + 1) == '/'.code) { d--; i += 2 } else i++ }
            }
            c == '"'.code && at(i + 1) == '"'.code && at(i + 2) == '"'.code -> {
                i += 3
                while (i < n && !(at(i) == '"'.code && at(i + 1) == '"'.code && at(i + 2) == '"'.code)) i++
                i += 3; while (at(i) == '"'.code) i++
            }
            c == '"'.code -> {
                i++
                while (i < n && at(i) != '"'.code && at(i) != '\n'.code) {
                    if (at(i) == '\\'.code) i += 2
                    else if (at(i) == '$'.code && at(i + 1) == '{'.code) {
                        var d = 0
                        while (i < n) { if (at(i) == '{'.code) d++ else if (at(i) == '}'.code) { d--; if (d == 0) break }; i++ }
                        i++
                    } else i++
                }
                i++
            }
            c == '\''.code -> { i += if (at(i + 1) == '\\'.code) 3 else 2; while (i < n && at(i) != '\''.code && at(i) != '\n'.code) i++; i++ }
            c == '{'.code -> { depth++; i++ }
            c == '}'.code -> { depth--; i++ }
            c == '('.code -> { parens++; i++ }
            c == ')'.code -> { parens--; i++ }
            c == '`'.code || (isIdentifierByte(c) && c !in '0'.code..'9'.code) -> {
                val w = wordAt(i)!!
                i = if (c == '`'.code) w.second + 1 else w.second
                if (w.second > w.first) {
                    if (rc == refs.size) refs = refs.copyOf(rc * 2)
                    refs[rc++] = identifierHash(src, w.first, w.second)
                }
                if (depth == 0 && parens == 0 && c != '`'.code) when (text(w)) {
                    "package" -> { val (p, k) = path(skipBlank(i)); pkg = p; i = k }
                    "import" -> { val (p, k) = path(skipBlank(i)); if (p.isNotEmpty()) imports += p; i = k }
                    "class", "interface", "object", "typealias" -> {
                        val k = skipBlank(i)
                        if (at(k) != '.'.code && at(k) != ':'.code) wordAt(k)?.let { declared += text(it) }
                    }
                    "fun", "val", "var" -> {
                        val k = skipBlank(i)
                        val next = wordAt(k)?.let(::text)
                        if (next != "interface") callableName(i)?.let { declared += it }
                    }
                }
            }
            else -> i++
        }
    }
    val sorted = refs.copyOf(rc).also { it.sort() }
    var u = 0
    for (k in sorted.indices) if (k == 0 || sorted[k] != sorted[k - 1]) sorted[u++] = sorted[k]
    return KotlinHeader(pkg, imports, declared, sorted.copyOf(u))
}

/**
 * Kotlin source dependency graph over a set of files: dependent CSRs keyed by the depended-on file.
 * Import edges: a wildcard `P.*` names every file declaring package P; an explicit `P.Name…` names
 * the files of P (longest declared prefix) that declare `Name` at top level, else every file of P.
 * Package edges: a file references a same-package sibling when one of its identifiers equals a
 * top-level name the sibling declares.
 */
class KotlinSourceGraph(val headers: Array<KotlinHeader>) {
    val packages = HashMap<String, IntArray>()
    val importLines: Int
    val unresolvedImports: Int
    val importOffsets: IntArray
    val importTargets: IntArray
    val packageOffsets: IntArray
    val packageTargets: IntArray

    init {
        val n = headers.size
        val byPkg = HashMap<String, MutableList<Int>>()
        for (f in 0 until n) byPkg.getOrPut(headers[f].pkg) { mutableListOf() } += f
        for ((p, fs) in byPkg) packages[p] = fs.toIntArray()
        val pkgIndex = HashMap<String, Int>()
        for (p in packages.keys) pkgIndex[p] = pkgIndex.size
        // (pkg,name) key32 << 32 | file, sorted: the top-level declaration index.
        var decl = LongArray(1024); var dc = 0
        for (f in 0 until n) {
            val pi = pkgIndex[headers[f].pkg]!!
            for (name in headers[f].declared) {
                if (dc == decl.size) decl = decl.copyOf(dc * 2)
                decl[dc++] = (declarationKey(pi, identifierHash(name)).toLong() shl 32) or f.toLong()
            }
        }
        decl = decl.copyOf(dc).also { it.sort() }
        fun declarers(key: Int, sink: (Int) -> Unit) {
            val probe = key.toLong() shl 32
            var lo = 0; var hi = dc
            while (lo < hi) { val m = (lo + hi) ushr 1; if (decl[m] < probe) lo = m + 1 else hi = m }
            while (lo < dc && (decl[lo] ushr 32).toInt() == key) { sink((decl[lo] and 0xffffffffL).toInt()); lo++ }
        }
        var imp = LongArray(4096); var ic = 0
        var sib = LongArray(4096); var sc = 0
        var lines = 0; var unresolved = 0
        for (f in 0 until n) {
            val h = headers[f]
            for (path in h.imports) {
                lines++
                val wildcard = path.endsWith(".*")
                val segs = path.removeSuffix(".*").split('.')
                var cut = segs.size
                while (cut > 0 && segs.subList(0, cut).joinToString(".") !in packages) cut--
                if (cut == 0) { unresolved++; continue }
                val pkg = segs.subList(0, cut).joinToString(".")
                val files = packages[pkg]!!
                var hit = false
                if (!(wildcard && cut == segs.size)) {
                    declarers(declarationKey(pkgIndex[pkg]!!, identifierHash(segs[cut]))) { d ->
                        if (d != f) { if (ic == imp.size) imp = imp.copyOf(ic * 2); imp[ic++] = (d.toLong() shl 32) or f.toLong() }
                        hit = true
                    }
                }
                if (!hit) for (d in files) if (d != f) { if (ic == imp.size) imp = imp.copyOf(ic * 2); imp[ic++] = (d.toLong() shl 32) or f.toLong() }
            }
            val pi = pkgIndex[h.pkg]!!
            for (r in h.referenced) declarers(declarationKey(pi, r)) { d ->
                if (d != f) { if (sc == sib.size) sib = sib.copyOf(sc * 2); sib[sc++] = (d.toLong() shl 32) or f.toLong() }
            }
        }
        importLines = lines
        unresolvedImports = unresolved
        val ci = csr(n, imp, ic); importOffsets = ci.a; importTargets = ci.b
        val cs = csr(n, sib, sc); packageOffsets = cs.a; packageTargets = cs.b
    }

    val importEdges: Int get() = importTargets.size
    val packageEdges: Int get() = packageTargets.size

    /**
     * Breadth-first dependent rings 1..depth from [node]; each ring splits into files first
     * reached through an import edge and files first reached only through a package edge.
     */
    fun rings(node: Int, depth: Int): List<Twin<IntArray>> {
        val seen = BooleanArray(headers.size).also { it[node] = true }
        var ring = intArrayOf(node)
        val out = ArrayList<Twin<IntArray>>()
        for (level in 1..depth) {
            val viaImport = ArrayList<Int>(); val viaPackage = ArrayList<Int>()
            for (m in ring) for (k in importOffsets[m] until importOffsets[m + 1]) { val t = importTargets[k]; if (!seen[t]) { seen[t] = true; viaImport += t } }
            for (m in ring) for (k in packageOffsets[m] until packageOffsets[m + 1]) { val t = packageTargets[k]; if (!seen[t]) { seen[t] = true; viaPackage += t } }
            out += viaImport.toIntArray() j viaPackage.toIntArray()
            if (viaImport.isEmpty() && viaPackage.isEmpty()) break
            ring = viaImport.toIntArray() + viaPackage.toIntArray()
        }
        return out
    }
}

fun declarationKey(pkgIndex: Int, nameHash: Int): Int = nameHash xor (pkgIndex * -0x61c88647)

/** Sorted, deduplicated `(from << 32) | to` pairs → CSR offsets/targets. */
fun csr(n: Int, pairs: LongArray, count: Int): Twin<IntArray> {
    val s = pairs.copyOf(count).also { it.sort() }
    var u = 0
    for (k in 0 until count) if (k == 0 || s[k] != s[k - 1]) s[u++] = s[k]
    val offsets = IntArray(n + 1)
    val targets = IntArray(u)
    for (k in 0 until u) { offsets[(s[k] ushr 32).toInt() + 1]++; targets[k] = (s[k] and 0xffffffffL).toInt() }
    for (k in 0 until n) offsets[k + 1] += offsets[k]
    return offsets j targets
}
