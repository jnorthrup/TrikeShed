package borg.trikeshed.lcnc

import borg.trikeshed.lib.*

/** Omitted list occurrences retain their executed positions until the root frame is exported. */
object LcncProjection {
    object Absent

    private class Limit(val reason: String) : Exception(reason)

    /** Export the entire frame or an explicit unavailable result; internal slots never escape. */
    fun frame(outputs: Map<String, Map<String, Any?>>, bindings: Map<String, Any?>,
              terms: LcncBindingTerms? = null,
              outputOrigins: Map<String, Map<String, LcncBindingOrigins>> = emptyMap()): LcncRetainedFrame {
        var nodes = 0
        fun check(depth: Int) {
            if (++nodes > 65536) throw Limit("work_limit")
            if (depth > 32) throw Limit("depth_limit")
            terms?.account()
        }
        return try {
            val global = if (terms == null) emptySeriesOf() else {
                val parts = ArrayList<LcncBindingOrigins>()
                for ((node, ports) in outputOrigins) for ((port, origins) in ports) {
                    if (outputs[node]?.containsKey(port) == true) parts.add(terms.prefix(origins,
                        s_[Either.left<String, Int>(node), Either.left<String, Int>(port)]))
                }
                terms.join(parts.toSeries())
            }
            // A term is evaluated once for the final binding set, never independently per port.
            val masked = if (terms == null) outputs else terms.mask(outputs, global, terms.evaluate(bindings.keys))
            val byPath = HashMap<List<Any>, MutableList<Int>>()
            for (origin in global.view) {
                val path = origin.a.view.map { it.fold({ key -> key }, { index -> index }) }
                byPath.getOrPut(path) { ArrayList() }.add(origin.b)
            }
            val compacted = ArrayList<Join<LcncBindingPath, Int>>()
            fun path(value: List<Any>): LcncBindingPath = value α {
                if (it is String) Either.left<String, Int>(it) else Either.right<String, Int>(it as Int)
            }
            fun value(item: Any?, depth: Int, before: List<Any>?, after: List<Any>?): Any? {
                check(depth)
                if (item === Absent) throw Limit("unsupported_value")
                if (before != null && after != null) for (term in byPath[before].orEmpty())
                    compacted.add(path(after) j term)
                return when (item) {
                    is Map<*, *> -> buildMap {
                        for ((key, child) in item) {
                            // Keys remain data too; do not erase unsupported/cyclic keys as a side effect.
                            val projectedKey = value(key, depth + 1, null, null)
                            if (child === Absent) check(depth + 1)
                            else put(projectedKey, value(child, depth + 1,
                                if (before != null && key is String) before + key else null,
                                if (after != null && key is String) after + key else null))
                        }
                    }
                    is List<*> -> buildList {
                        for ((index, child) in item.withIndex()) {
                            if (child === Absent) check(depth + 1)
                            else add(value(child, depth + 1, before?.plus(index), after?.plus(size)))
                        }
                    }
                    is Array<*> -> buildList {
                        for ((index, child) in item.withIndex()) {
                            if (child === Absent) check(depth + 1)
                            else add(value(child, depth + 1, before?.plus(index), after?.plus(size)))
                        }
                    }.toTypedArray()
                    else -> item
                }
            }
            @Suppress("UNCHECKED_CAST")
            val projectedOutputs = value(masked, 0, emptyList(), emptyList()) as Map<String, Map<String, Any?>>
            @Suppress("UNCHECKED_CAST")
            val projectedBindings = value(bindings, 0, null, null) as Map<String, Any?>
            LcncRetainedFrame(projectedOutputs, projectedBindings,
                origins = terms?.export(compacted.toSeries(), bindings.keys))
        } catch (limit: Limit) {
            LcncRetainedFrame(unavailable = limit.reason)
        } catch (limit: LcncBindingTerms.Limit) {
            LcncRetainedFrame(unavailable = limit.reason)
        } catch (_: IllegalArgumentException) {
            LcncRetainedFrame(unavailable = "binding_origin_format")
        }
    }
}
