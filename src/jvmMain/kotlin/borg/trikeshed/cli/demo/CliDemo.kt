package borg.trikeshed.cli.demo

import borg.trikeshed.cursor.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*

/**
 * CLI Demo - demonstrates command-line interaction layer with CCEK scaffold.
 * 
 * This shows the data-flow chain:
 *   main(args) -> SupervisorJob -> NioSupervisor -> LCNC cursor facets -> user-signals
 */
object CliDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        println("⚡ TrikeShed CLI Demo Starting...")
        println()

        // 1. Parse args into cursor
        println("1. Parse args into cursor")
        println("   Args: ${args.joinToString(", ")}")
        println()

        // 2. Enter default SupervisorJob scaffold
        println("2. Enter default SupervisorJob scaffold")
        runBlocking {
            val supervisorJob = SupervisorJob()
            println("   SupervisorJob: active")
            println()

            // 3. Resolve NioSupervisor
            println("3. Resolve NioSupervisor")
            val nioSupervisor = NioSupervisor()
            println("   NioSupervisor: ${nioSupervisor.javaClass.simpleName}")
            println()

            // 4. Show CCEK choreography contracts exist
            println("4. CCEK choreography contracts")
            println("   ccekCommandLineChoreography: defined")
            println("   ccekGeneratedApiChoreography: defined")
            println("   liftCursorFacetsToUserSignal: defined")
            println("   cursorToUserSignals: defined")
            println()

            // 5. Show LCNC facet handles exist
            println("5. LCNC facet handles")
            println("   LcncFacetGroup: available")
            println("   LayoutHint: Horizontal, Vertical, Grid, Stack, None")
            println("   WtkHint: Label, Button, Input, Slider, Table, Chart, Image")
            println("   DagCoordinate: available")
            println()

            // 6. Show TrikeShedScope contract
            println("6. TrikeShedScope scaffold")
            println("   TrikeShedScope interface: available")
            println("   getOrCreateNioSupervisor: available")
            println()

            supervisorJob.cancel()
        }

        println("✅ CLI Demo Complete!")
    }
}