package borg.trikeshed.cursor

/**
 * TypedefProductionSystem main — demonstrates the CRMS data plane.
 *
 * Run with: java -cp TrikeShed-jvm-1.0.jar borg.trikeshed.cursor.TypedefProductionSystemMain
 */
object TypedefProductionSystemMain {

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== TypedefProductionSystem ===")
        println()

        // ── 1. Register AdjacentRule algebra ──────────────────────────

        // Rule: trace all CALL sites on org..types.* typedefs
        TypedefProductionSystem.addRule(
            TypedefProductionSystem.AdjacentRule(
                ownerPattern = "org//types/**",
                opcode = TypedefProductionSystem.OP_CALL,
                phase = 0,  // BEFORE
                typedefName = "org..types.Chain",
                depth = 2,
                methodFilter = "*"
            )
        )

        // Rule: trace PROPERTY access on all typedefs
        TypedefProductionSystem.addRule(
            TypedefProductionSystem.AdjacentRule(
                ownerPattern = "**",
                opcode = TypedefProductionSystem.OP_PROPERTY,
                phase = 0,
                typedefName = "org..types.Any",
                depth = 1,
                methodFilter = "get*"
            )
        )

        // Rule: trace ALLOC sites on org..collections.*
        TypedefProductionSystem.addRule(
            TypedefProductionSystem.AdjacentRule(
                ownerPattern = "org//collections/**",
                opcode = TypedefProductionSystem.OP_ALLOC,
                phase = 0,
                typedefName = "org..collections.List",
                depth = 3,
                methodFilter = "*"
            )
        )

        println("Rules registered: 3")
        println("  CALL   org//types/**        depth=2")
        println("  PROP   **                       depth=1, method=get*")
        println("  ALLOC  org//collections/**  depth=3")
        println()

        // ── 2. Wire SlabSubscriber ───────────────────────────────────

        TypedefProductionSystem.subscriber = object : TypedefProductionSystem.SlabSubscriber {
            override fun onSlab(
                slab: Array<TypedefProductionSystem.TraceEvent>,
                count: Int,
                epoch: Long,
                nanoStart: Long,
                nanoEnd: Long
            ) {
                println("── slab #${epoch} ─────────────────────────────────────")
                println("  count   : $count")
                println("  duration : ${nanoEnd - nanoStart} ns")
                println()

                // CRMS fold
                val cells = TypedefProductionSystem.fold(slab)
                println("  CRMS conflict cells (eigsort by depth):")
                for (cell in cells.take(10)) {
                    val beforeStr = cell.before?.let { "${it.opcodeName()} ${it.typedefName()}" } ?: "—"
                    val afterStr  = cell.after?.let  { "${it.opcodeName()} ${it.typedefName()}" } ?: "—"
                    println("    hash=0x${Integer.toHexString(cell.callsiteHash).padStart(4,'0')} " +
                            "depth=${cell.depth} " +
                            "resolved=${cell.resolved} " +
                            "BEFORE=$beforeStr AFTER=$afterStr")
                }
                if (cells.size > 10) {
                    println("    ... and ${cells.size - 10} more cells")
                }
                println()

                // Pool stats
                println("  InternPool : ${TypedefProductionSystem.InternPool.size()} entries")
                println("  TypedefTable: ${TypedefProductionSystem.TypedefTable.size()} entries")
                println()
            }
        }

        // ── 3. Activate and publish synthetic events ─────────────────

        TypedefProductionSystem.active = true

        println("Publishing synthetic events...")
        println()

        // Simulate CALL events (BEFORE + AFTER pairs)
        val callSites = listOf(
            Triple("org..types.Chain", "org..types.Chain.push", 0x0001),
            Triple("org..types.Chain", "org..types.Chain.pop",  0x0002),
            Triple("org..types.Chain", "org..types.Chain.head", 0x0003),
        )

        for ((typedef, method, site) in callSites) {
            TypedefProductionSystem.publish(
                TypedefProductionSystem.OP_CALL, typedef, method, site, 2, false  // BEFORE
            )
            TypedefProductionSystem.publish(
                TypedefProductionSystem.OP_CALL, typedef, method, site, 2, true   // AFTER
            )
        }

        // Simulate PROPERTY events
        val propSites = listOf(
            Triple("org..types.Any", "getValue", 0x0010),
            Triple("org..types.Any", "getType",  0x0011),
        )

        for ((typedef, method, site) in propSites) {
            TypedefProductionSystem.publish(
                TypedefProductionSystem.OP_PROPERTY, typedef, method, site, 1, false
            )
            TypedefProductionSystem.publish(
                TypedefProductionSystem.OP_PROPERTY, typedef, method, site, 1, true
            )
        }

        // Simulate ALLOC events
        val allocSites = listOf(
            Triple("org..collections.List", "", 0x0100),
            Triple("org..collections.List", "", 0x0101),
        )

        for ((typedef, _, site) in allocSites) {
            TypedefProductionSystem.publish(
                TypedefProductionSystem.OP_ALLOC, typedef, "", site, 3, false
            )
            TypedefProductionSystem.publish(
                TypedefProductionSystem.OP_ALLOC, typedef, "", site, 3, true
            )
        }

        // Force a flush
        TypedefProductionSystem.flush("manual")

        // ── 4. Wireproto dump ────────────────────────────────────────

        println("=== Wireproto dump ===")
        val wire = TypedefProductionSystem.drainToWireproto()
        println("wireproto length: ${wire.remaining()} bytes")
        println()

        // Decode records
        wire.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val pos = wire.position()
        var offset = 0
        while (wire.hasRemaining()) {
            val opcode  = wire.get()
            val phase   = wire.get()
            val tdIdx   = wire.short.toInt() and 0xFFFF
            val mIdx    = wire.int
            val site    = wire.int
            val seq     = wire.int
            val nano    = wire.long
            val depth   = wire.get()
            wire.get()  // pad
            val csh     = wire.short.toInt() and 0xFFFF

            val opName  = when (opcode.toInt() and 0xFF) {
                0x10 -> "CALL"; 0x11 -> "ALLOC"; 0x12 -> "RETURN"
                0x13 -> "PROP";  0x14 -> "PARAM"; 0x15 -> "CAST"
                else -> "???"
            }
            val phaseStr = if (phase == 0.toByte()) "BEFORE" else "AFTER "

            println("  [${String.format("%04d", offset)}] $phaseStr $opName " +
                    "td=${String.format("%4d", tdIdx)} " +
                    "method=${String.format("%4d", mIdx)} " +
                    "site=0x${Integer.toHexString(site)} " +
                    "seq=${String.format("%4d", seq)} " +
                    "depth=$depth " +
                    "hash=0x${Integer.toHexString(csh).padStart(4,'0')}")
            offset += 24
        }

        println()
        println("=== Done ===")
    }
}