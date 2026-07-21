package borg.trikeshed.fetch

import borg.trikeshed.platform.executeProcess
import borg.trikeshed.platform.getProgramName
import borg.trikeshed.reactor.Event
import borg.trikeshed.reactor.Reactor
import borg.trikeshed.reactor.EventType // Assuming EventType is accessible
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

// Define specific event types for fetching, if not already generic enough in base Reactor
object FetchEventTypes {
    val FETCH_REQUEST = EventType.REQUEST // Or define a custom EventType if needed
    val FETCH_RESPONSE = EventType.RESPONSE // Or define a custom EventType
}

class FetchingReactor(
    name: String = "fetching-reactor",
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val symlinkPathResolver: () -> String = { defaultSymlinkPath() }
) : Reactor<Any>(name) { // Using Reactor<Any> to handle various event types

    init {
        on(FetchEventTypes.FETCH_REQUEST) { event ->
            val fetchRequest = event.data as? FetchRequest
            if (fetchRequest != null) {
                handleFetchRequest(fetchRequest)
            } else {
                println("$name: Received event with unexpected data type for FETCH_REQUEST: ${event.data}")
            }
        }
    }

    private fun handleFetchRequest(request: FetchRequest) {
        scope.launch {
            val commandName = when (request.tool) {
                FetchTool.CURL -> "trike-curl"
                FetchTool.ARIA2C -> "trike-aria2c"
            }
            
            // Resolve the full path to the symlink/executable
            // This is a simplified assumption; robust path resolution might be needed.
            // val commandPath = symlinkPathResolver() + commandName
            // For now, assume commandName is in PATH or is the main executable itself if symlinked.
            // The `executeProcess` function takes the command name directly.
            // If `getProgramName()` gives the full path to the current executable,
            // we could try to use its directory.

            println("$name: Executing $commandName with args: ${request.args}")

            try {
                // We need to ensure `executeProcess` is suspend or called from an appropriate dispatcher
                // The current `executeProcess` is not suspend, so this direct call is okay.
                // However, for non-blocking reactor, long operations should be offloaded.
                // The `scope.launch` already helps here.
                val result = executeProcess(commandName, request.args)

                val response = FetchResponse(
                    request = request,
                    success = result.exitCode == 0,
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    stderr = result.stderr,
                    correlationId = request.correlationId
                )
                // Emit the response back into this reactor or a general event bus
                // Assuming emit can take 'Any' due to Reactor<Any>
                emit(Event(FetchEventTypes.FETCH_RESPONSE, response, timestampNow())) 
            } catch (e: Exception) {
                println("$name: Error executing fetch request for ${request.url}: ${e.message}")
                val errorResponse = FetchResponse(
                    request = request,
                    success = false,
                    exitCode = -1, // Indicate internal error
                    stdout = "",
                    stderr = "Reactor internal error: ${e.message}",
                    correlationId = request.correlationId
                )
                emit(Event(FetchEventTypes.FETCH_RESPONSE, errorResponse, timestampNow()))
            }
        }
    }

    // Helper to get current timestamp for events
    private fun timestampNow(): Long {
        // Using a simple placeholder. In a real KMP app, use kotlinx-datetime.
        // return Clock.System.now().toEpochMilliseconds() // If kotlinx-datetime is available
        return 0L // Placeholder
    }
}

// Default symlink path resolution strategy (placeholder)
// This might involve getting the current executable's directory.
private fun defaultSymlinkPath(): String {
    // val currentProgramPath = getProgramName() // This is expect
    // val file = File(currentProgramPath) // Need KMP file abstraction or platform specific
    // return file.parent?.let { "$it/" } ?: ""
    return "" // Assume it's in PATH
}

// Example usage (conceptual, would be in main application setup)
/*
fun main() = runBlocking {
    val fetchingReactor = FetchingReactor()
    fetchingReactor.start() // Assuming Reactor.start() is suspend or handles its own scope

    // Listen for responses (example)
    fetchingReactor.on(FetchEventTypes.FETCH_RESPONSE) { event ->
        val response = event.data as FetchResponse
        println("Fetcher got response: Success: ${response.success}, stdout: ${response.stdout.take(50)}...")
    }

    val request = FetchRequest(
        tool = FetchTool.CURL,
        url = "http://example.com",
        args = listOf("-i", "http://example.com"),
        correlationId = "test123"
    )
    
    // Emit the request into the fetchingReactor
    // The event type for emitting needs to match what `on` expects.
    // If Reactor.emit takes Event<T>, and FetchingReactor is Reactor<Any>, this is fine.
    fetchingReactor.emit(Event(FetchEventTypes.FETCH_REQUEST, request, 0L))

    delay(5000) // Wait for async operation
    fetchingReactor.stop()
}
*/
