package borg.literbike.ccek.http

/**
 * Lean HTTP/1.1 Server - relaxfactory pattern
 *
 * Zero-copy, minimal-allocation HTTP server
 */

/**
 * HTTP request handler interface
 */
fun interface HttpHandler {
    fun handle(session: HttpSession)
}

/**
 * Simple closure-based handler
 */
fun interface FnHandler : HttpHandler {
    override fun handle(session: HttpSession)
}

/**
 * HTTP Server event handler
 */
class HttpEventHandler(
    private val serverName: String
) {
    private val routes = mutableMapOf<String, HttpHandler>()
    private var defaultHandler: HttpHandler? = null
    private val sessions = mutableMapOf<Int, HttpSession>()

    companion object {
        fun new(serverName: String): HttpEventHandler = HttpEventHandler(serverName)
    }

    /**
     * Register route handler
     */
    fun registerRoute(path: String, handler: HttpHandler) {
        routes[path] = handler
    }

    /**
     * Register closure handler
     */
    fun registerRouteFn(path: String, handler: (HttpSession) -> Unit) {
        registerRoute(path, FnHandler { handler(it) })
    }

    /**
     * Set default handler
     */
    fun setDefaultHandler(handler: HttpHandler) {
        defaultHandler = handler
    }

    /**
     * Set default handler from closure
     */
    fun setDefaultHandlerFn(handler: (HttpSession) -> Unit) {
        setDefaultHandler(FnHandler { handler(it) })
    }

    /**
     * Route request to handler
     */
    fun routeRequest(session: HttpSession) {
        val path = session.path()
        val routePath = path?.substringBefore('?')

        val handler = routePath?.let { routes[it] } ?: defaultHandler

        if (handler != null) {
            handler.handle(session)
        } else {
            session.prepareResponse(HttpStatus.Status404, MimeTypes.TEXT_PLAIN, "404 Not Found".toByteArray())
        }
    }

    /**
     * Get or create session for fd
     */
    fun getSession(fd: Int): HttpSession? = sessions[fd]

    fun getSessionMutable(fd: Int): HttpSession? = sessions[fd]

    /**
     * Add a new session
     */
    fun addSession(fd: Int, session: HttpSession) {
        sessions[fd] = session
    }

    /**
     * Remove a session
     */
    fun removeSession(fd: Int) {
        sessions.remove(fd)
    }

    fun activeSessions(): Int = sessions.size
}

/**
 * HTTP Server
 */
class HttpServer(
    val name: String,
    val addr: String,
    val port: Int
) {
    val handler: HttpEventHandler = HttpEventHandler(name)
    private var running: Boolean = false

    companion object {
        fun new(name: String, addr: String, port: Int): HttpServer = HttpServer(name, addr, port)
    }

    /**
     * Register route handler
     */
    fun route(path: String, handler: HttpHandler) {
        this.handler.registerRoute(path, handler)
    }

    /**
     * Register closure handler
     */
    fun routeFn(path: String, handler: (HttpSession) -> Unit) {
        this.handler.registerRouteFn(path, handler)
    }

    fun isRunning(): Boolean = running

    fun stop() {
        running = false
    }
}

/**
 * Helper: send simple HTTP response
 */
fun sendResponse(session: HttpSession, status: HttpStatus, contentType: String, body: ByteArray) {
    session.prepareResponse(status, contentType, body)
}

/**
 * Helper: send JSON response
 */
fun sendJson(session: HttpSession, status: HttpStatus, json: String) {
    session.prepareResponse(status, MimeTypes.APPLICATION_JSON, json.toByteArray())
}

/**
 * Helper: send HTML response
 */
fun sendHtml(session: HttpSession, status: HttpStatus, html: String) {
    session.prepareResponse(status, MimeTypes.TEXT_HTML, html.toByteArray())
}

/**
 * Helper: send redirect
 */
fun sendRedirect(session: HttpSession, location: String) {
    session.parser.setStatus(HttpStatus.Status302)
    session.parser.setHeader(Headers.LOCATION, location)
    session.prepareResponse(HttpStatus.Status302, MimeTypes.TEXT_PLAIN, "Redirect".toByteArray())
}
