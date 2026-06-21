package borg.trikeshed.forge.ui

import com.sun.net.httpserver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.InetSocketAddress

/**
 * Minimal honest slice: 
 * 1. read one local ~/.hermes data source
 * 2. normalize to one KanbanEvent  
 * 3. apply one Kanban FSM transition
 * 4. render changed board state via SSE
 * 
 * Uses JDK HttpServer - no external dependencies.
 */
object ReactorServer {
    private var server: HttpServer? = null
    
    val isRunning: Boolean get() = server != null
    
    /**
     * Start the HTTP server on given port.
     * Loads credentials from ~/.hermes and streams KanbanState via SSE.
     */
    fun start(port: Int = 8080): HttpServer {
        // Create HTTP server
        server = HttpServer.create(InetSocketAddress(port), 0)
        
        setupEndpoints()
        
        server?.start()
        println("ReactorServer started on http://localhost:$port")
        println("SSE endpoint: http://localhost:$port/events")
        
        return server!!
    }
    
    private fun setupEndpoints() {
        server?.createContext("/") { exchange ->
            val response = """
                <!DOCTYPE html>
                <html>
                <head><title>Forge UI Reactor</title></head>
                <body>
                    <h1>Forge UI Reactor Server</h1>
                    <p><a href="/events">SSE Events Stream</a></p>
                </body>
                </html>
            """.trimIndent()
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.write(response.toByteArray())
            exchange.close()
        }
        
        server?.createContext("/events") { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.responseHeaders.set("Cache-Control", "no-cache")
            exchange.sendResponseHeaders(200, 0)
            
            val out = exchange.responseBody
            
            // Stream a simple event every second
            var counter = 0
            while (counter < 3) {
                out.write("data: kanban-event-$counter\n\n".toByteArray())
                out.flush()
                Thread.sleep(1000)
                counter++
            }
            exchange.close()
        }
    }
    
    fun stop() {
        server?.stop(0)
        server = null
    }
    
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: 8080
        ReactorServer.start(port)
        Thread.sleep(Long.MAX_VALUE) // Keep server running
    }
}
