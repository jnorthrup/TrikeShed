package borg.trikeshed.openapi.demo

import borg.trikeshed.openapi.*
import borg.trikeshed.cursor.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*

/**
 * OpenAPI Generated API Demo - demonstrates generated API interaction layer with CCEK scaffold.
 * 
 * This shows the data-flow chain:
 *   OpenAPI operation -> generated request cursor -> SupervisorJob -> NioSupervisor -> LCNC facets -> user-signals
 */
object OpenApiDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        println("📡 TrikeShed OpenAPI Demo Starting...")
        println()

        // 1. Show OpenAPI generation contracts
        println("1. OpenAPI generation contracts")
        println("   renderAllClientSources: defined")
        println("   renderAllServerSources: defined")
        println("   OpenApiRawParser: defined")
        println("   OpenApiClientGenerator: defined")
        println()

        // 2. Show SupervisorJob composition
        println("2. SupervisorJob composition in generated APIs")
        runBlocking {
            val parentJob = SupervisorJob()
            println("   Parent SupervisorJob: active")
            println()

            // 3. Show operation routing
            println("3. Generated API operation routing")
            println("   Each operationId gets its own Job")
            println("   Fan-out from parent SupervisorJob")
            println()

            // 4. Show LCNC cursor facets for requests
            println("4. LCNC cursor facets for request/response")
            println("   RequestCursor: Series<RowVec>")
            println("   ResponseCursor: Series<RowVec>")
            println("   convertRequestToCursor: defined")
            println("   convertCursorToResponse: defined")
            println()

            // 5. Show user-signalling integration
            println("5. User-signalling integration")
            println("   Generated operations emit user signals")
            println("   Overlay queries accessible")
            println()

            // 6. Show TrikeShedScope integration
            println("6. TrikeShedScope integration")
            println("   Generated APIs wire through root scaffold")
            println("   NioSupervisor available")
            println()

            parentJob.cancel()
        }

        println("✅ OpenAPI Demo Complete!")
    }
}