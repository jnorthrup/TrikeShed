package org.xvm.activejs

import org.xvm.cursor.PointcutFacet
import org.xvm.cursor.ClassFileTaxonomy
import borg.trikeshed.parse.confix.SaxEvent
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Jep466ActiveJsBridge — bridges JEP 466 ClassFile API to ActiveJS taxonomy.
 *
 * On JVM: delegates to Jep466Cursor (uses java.lang.classfile)
 * On JS/WASM/Native: provides a minimal classfile parser
 *                    that emits equivalent Confix SAX events.
 *
 * Architecture:
 *   ClassFile bytes → Jep466PlatformParser → Confix SAX events
 *                    → ActiveJsTaxonomy.register() → LivePointcutCursor
 */
object Jep466ActiveJsBridge {

    /**
     * Parse a ClassFile byte array and populate the ActiveJsTaxonomy.
     * Emits Confix SAX events through the provided consumer.
     *
     * @param bytes Raw ClassFile bytes
     * @param taxonomy Target taxonomy to populate
     * @param saxConsumer Consumer for Confix SAX events
     * @return Number of coordinate rows registered
     */
    fun walkClassFile(
        bytes: ByteArray,
        taxonomy: ActiveJsTaxonomy,
        saxConsumer: (SaxEvent) -> Unit = { },
    ): Int {
        // Emit SAX events (delegates to platform-specific impl)
        val events = mutableListOf<SaxEvent>()
        Jep466PlatformParser.walkClassFile(bytes) { event ->
            events.add(event)
            saxConsumer(event)
        }

        // Extract coordinate rows from SAX events and register
        val rowsRegistered = extractCoordinateRows(events, taxonomy)
        return rowsRegistered
    }

    /**
     * Parse ClassFile and return Series of coordinate rows (PRELOAD lazy).
     * No taxonomy mutation — pure function for cold queries.
     */
    fun parseToSeries(bytes: ByteArray): Series<ActiveJsTaxonomy.CoordinateRow> = liveSeries(
        count = { countCoordinateRows(bytes) },
        access = { idx -> coordinateRowAt(bytes, idx) },
    )

    // ── Coordinate extraction from SAX events ───────────────────────────────

    private fun extractCoordinateRows(events: List<SaxEvent>, taxonomy: ActiveJsTaxonomy): Int {
        var count = 0
        var currentClass = ""
        var currentMethod = ""
        var inClass = false
        var inMethods = false
        var methodIndex = 0

        for (event in events) {
            when (event) {
                is SaxEvent.Enter -> {
                    when (event.a) {
                        is Int -> {
                            // IoObject = class enter
                            if (event.a == 0) { // IoObject placeholder
                                inClass = true
                            }
                            // IoArray for methods
                            if (event.a == 1) { // IoArray placeholder
                                inMethods = true
                                methodIndex = 0
                            }
                        }
                        is String -> {
                            if (inClass && currentClass.isEmpty()) {
                                currentClass = event.a
                            } else if (inMethods) {
                                currentMethod = event.a
                                // Register coordinate row for this method
                                val poolId = computePoolId(currentClass, currentMethod, methodIndex)
                                taxonomy.register(ActiveJsTaxonomy.CoordinateRow(
                                    symbolName = "$currentClass.$currentMethod",
                                    ownerType = currentClass,
                                    methodOrField = currentMethod,
                                    classfileCoord = "$currentClass#$currentMethod",
                                    cpIndex = methodIndex,
                                    descriptor = "()V", // Would need real descriptor from constant pool
                                    xvmTypeInfo = "",
                                    pointcutKind = inferPointcutKind(currentMethod),
                                    poolId = poolId,
                                    activeJsFacet = activeJsFacetFromKind(inferPointcutKind(currentMethod)),
                                ))
                                count++
                                methodIndex++
                            }
                        }
                        else -> {}
                    }
                }
                is SaxEvent.Leave -> {
                    if (event.a == 0) { // IoObject leave
                        inClass = false
                        currentClass = ""
                    } else if (event.a == 1) { // IoArray leave
                        inMethods = false
                        currentMethod = ""
                    }
                }
            }
        }
        return count
    }

    private fun countCoordinateRows(bytes: ByteArray): Int {
        // Estimate: each method in classfile becomes one coordinate row
        // In real impl, parse constant pool and method table
        return 0 // Placeholder - requires full classfile parse
    }

    private fun coordinateRowAt(bytes: ByteArray, idx: Int): ActiveJsTaxonomy.CoordinateRow {
        return ActiveJsTaxonomy.CoordinateRow(
            symbolName = "unknown",
            ownerType = "unknown",
            methodOrField = "unknown",
            classfileCoord = "unknown",
            cpIndex = idx,
            descriptor = "()V",
            xvmTypeInfo = "",
            pointcutKind = 0x10,
            poolId = idx,
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun computePoolId(className: String, methodName: String, index: Int): Int {
        var h = className.hashCode()
        h = 31 * h + methodName.hashCode()
        h = 31 * h + index
        return h
    }

    private fun inferPointcutKind(methodName: String): Int {
        return when {
            methodName.startsWith("<init>") -> 0x34 // CONSTR
            methodName.startsWith("get") || methodName.startsWith("is") -> 0xA5 // L_GET
            methodName.startsWith("set") -> 0xA6 // L_SET
            methodName.contains("await") || methodName.contains("async") -> 0x4C // RETURN (promise)
            else -> 0x10 // CALL
        }
    }

    private fun activeJsFacetFromKind(kind: Int): ActiveJsFacet = when {
        kind in 0x10..0x1F -> ActiveJsFacet.JsFunction
        kind in 0x20..0x2F -> ActiveJsFacet.JsFunction
        kind in 0x34..0x37 -> ActiveJsFacet.JsPromise
        kind in 0x38..0x3B -> ActiveJsFacet.JsObject
        kind in 0x4C..0x4F -> ActiveJsFacet.JsPromise
        kind in 0xA5..0xA8 -> ActiveJsFacet.JsProxy
        else -> ActiveJsFacet.Unfaceted
    }
}

/**
 * Platform-specific classfile parser.
 * On JVM, delegates to java.lang.classfile via Jep466Cursor.
 * On JS/WASM/Native, provides a minimal classfile parser for the JVM classfile format.
 */
private expect object Jep466PlatformParser {
    fun walkClassFile(bytes: ByteArray, action: (SaxEvent) -> Unit)
}