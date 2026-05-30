package borg.trikeshed.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.cursor.CowSeriesHandle
import borg.trikeshed.cursor.CowSeriesBody
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

// ── ConfixOracleService ─────────────────────────────────────────────
//
// Kotlin implementation of ConfixOracleFacade.
// Each JIT worker has its own ConstantPool → ConfixOracleService instance.
// No cross-thread state sharing.
//
// Collapsed architecture:
//   getTypedefChain()  — single resolution entry, replaces getLattice() + getStepTarget() + resolveTypedefs()
//   isA()              — unchanged, used by calculateRelation()
//   addSource()        — unchanged, seed + notify flow

class ConfixOracleService : ConfixOracleFacade {

    private val oracle = TypeDefOracle()
    private val _edges = CowSeriesHandle<IsAEdge>(CowSeriesBody.of())
    private var listener: TypeDefListener? = null
    private var cachedRow: TypeDefOracleRow? = null

    // ── ConfixOracleFacade ──────────────────────────────────────────

    override fun addSource(sourceText: String, modulePath: String): Int {
        val beforeCount = oracle.size
        if (sourceText.isNotEmpty()) {
            oracle.parseTypeDefs(sourceText, modulePath)
        }
        val afterCount = oracle.size
        val newCount = afterCount - beforeCount

        if (newCount > 0) {
            cachedRow = null
            cachedRow = oracle.build()
            val row = cachedRow!!
            val lat = row.lattice

            for (i in beforeCount until afterCount) {
                val entry = row.entries[i]
                val subToken = row.byName(entry.name) ?: continue
                val supers = lat.directSupers(subToken)
                for (j in 0 until supers.size) {
                    _edges.add(subToken edgeTo supers[j])
                }
            }
        }

        listener?.onCompilationUnitResolved(modulePath, newCount)
        return newCount
    }

    /**
     * Unifies typedef resolution: single entry point for staircase chain.
     *
     * Walks the typedef chain from [typeName] to its terminal type using
     * the oracle's TypeDefEntry list. Parameters are stripped (only the
     * base typedef name is used for chain resolution).
     *
     * Replaces: getStepTarget() [old buildStaircase path],
     *           resolveTypedefs() [CBOR fallback],
     *           getLattice() [now dead code]
     */
    override fun getTypedefChain(modulePath: String, typeName: String): IntArray {
        val row = cachedRow ?: run {
            cachedRow = oracle.build()
            cachedRow!!
        }

        val startToken = row.byName(typeName) ?: return intArrayOf()

        // Build chain: startToken → ... → terminal
        // The oracle has all TypeDefEntry(name, referredToType) pairs.
        // We walk referredToType recursively until it stops being a typedef.
        val chain = mutableListOf<Int>()
        chain.add(startToken.poolIdx)

        var currentName = typeName
        val seen = mutableSetOf<String>()

        while (true) {
            if (!seen.add(currentName)) {
                // Cycle detected — terminate
                break
            }

            val entry = (0 until row.entries.a).map { row.entries.b(it) }.find { it.name == currentName } ?: break

            // Extract the base name from referredToType (strip params, unions, etc.)
            val baseName = extractBaseName(entry.referredToType)
            if (baseName == null || baseName == currentName) {
                // No further typedef hop
                break
            }

            val nextToken = row.byName(baseName)
            if (nextToken == null) {
                // Referred-to type is not a typedef — terminal reached
                break
            }

            chain.add(nextToken.poolIdx)
            currentName = baseName
        }

        return chain.toIntArray()
    }

    override fun isA(childPoolIdx: Int, parentPoolIdx: Int): Int? {
        val row = cachedRow ?: return 0 as Int?  // nothing built yet — UNKNOWN
        val result = row.lattice.isA(TypeToken(childPoolIdx), TypeToken(parentPoolIdx))
        return when {
            result == true  -> 2 as Int?
            result == false -> 1 as Int?
            else            -> 0 as Int?
        }
    }

    override fun setListener(listener: TypeDefListener?) {
        this.listener = listener
    }

    override fun edgeCount(): Int = _edges.a

    // ── observable access ──────────────────────────────────────────

    val edges: CowSeriesHandle<IsAEdge> get() = _edges

    /** Subscribe to edge mutations. @return cancel to unsubscribe */
    fun subscribeEdges(f: (Twin<Series<IsAEdge>>) -> Unit): () -> Unit = _edges.subscribe(f)

    // ── internal ───────────────────────────────────────────────────

    /**
     * Strip type parameters, unions, parentheses from a type expression
     * to get the base typedef name. E.g.:
     *   "Series<RowVec>"        → "Series"
     *   "Tuple<A, B>"           → "Tuple"
     *   "String|Int"            → null (not a typedef)
     *   "(Cursor|Series)"       → null (not a simple typedef)
     */
    private fun extractBaseName(typeExpr: String): String? {
        // Strip leading/trailing whitespace
        val expr = typeExpr.trim()
        if (expr.isEmpty()) return null

        // Strip type parameters: "Series<RowVec>" → "Series"
        val strippedParams = expr.replace(Regex("""<[^>]+>"""), "")

        // If there's still angle brackets after stripping params, it's malformed
        if (strippedParams.contains('<') || strippedParams.contains('>')) return null

        // Extract the identifier before any | or ( or space
        val namePart = strippedParams.split(Regex("""[|(\s]""")).firstOrNull() ?: return null
        val name = namePart.trim()

        // Must be a valid Java/Kotlin identifier
        if (name.isEmpty() || !name[0].isLetter() || name[0] == '_') return null

        return name
    }
}

typealias Subscription = () -> Unit