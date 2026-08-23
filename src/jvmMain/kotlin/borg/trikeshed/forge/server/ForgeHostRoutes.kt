package borg.trikeshed.forge.server

/**
 * The route table of the Forge host server as one list — the thing the OpenAPI sink
 * (`openapi/forge-host.openapi.yaml`) is checked against. Built-ins are the `when` in
 * `JvmKanbanServer.routeHttp`; the wires publish their own lists.
 */
object ForgeHostRoutes {
    val BUILT_IN: List<Pair<String, String>> = listOf(
        "GET" to "/api/health", "GET" to "/api/cap", "GET" to "/api/board", "GET" to "/api/metrics",
        "GET" to "/api/jules/surface", "GET" to "/api/jules/events",
        "POST" to "/api/submit", "POST" to "/api/donor", "POST" to "/api/invoke",
    )
    val ALL: List<Pair<String, String>> get() = BUILT_IN + VmWire.ROUTES + BlackboardWire.ROUTES
}
