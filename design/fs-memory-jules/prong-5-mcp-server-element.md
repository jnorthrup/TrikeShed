# Prong 5: MCP Server Element (Citation-Backed Search)

## CONTEXT

Paper 2607.26637v1, Section 2.2 (search agent contract): the search agent
returns (a, Gamma) = s(iota, q, M) where Gamma is a set of references into M
(file paths, optionally sections or lines) supporting answer a. Search is
read-only in intent.

MCP (Model Context Protocol) is the server-side protocol for exposing
resources and tools to external models. The paper's search-agent citation
contract maps directly to MCP's resource model: memory file paths are resource
URIs, and the search agent returns cited references (the Gamma set) as
traversed resource URIs.

The benefit: external agents (Claude Code, Codex, any MCP client) can query
the TrikeShed memory store with attributed answers, reusing the same ISAM
routes (Prong 2) and read tool set (Prong 3) that internal agents use.

TrikeShed reference patterns for CCEK server elements:

- `JvmKanbanServer` uses `LitebikeListenerElement` +
  `NuidFanoutElement` + `JvmLitebikeBindAdapter`, routes /api/health|cap|board.
- The lifecycle is: CREATED -> open() -> OPEN -> register(Protocol.*) ->
  activate() -> ACTIVE -> drain() -> DRAINING -> CLOSED.
- `JvmKanbanServer.run()` must register ALL `Protocol.entries` (9 protocols).

## TASK

1. Read `JvmKanbanServer` (search for the class in
   `src/jvmMain/kotlin/borg/trikeshed/`) to confirm the LitebikeListenerElement
   + NuidFanoutElement + JvmLitebikeBindAdapter pattern. Confirm the route
   registration and verifyRegistry() lifecycle.

2. Define `McpServerElement` in
  `src/jvmMain/kotlin/borg/trikeshed/mcp/McpServerElement.kt`:
   - A CCEK element implementing the five-state lifecycle (CREATED -> OPEN ->
     ACTIVE -> DRAINING -> CLOSED).
   - Opens on a configurable port (default: 9999, or a UNIX domain socket for
     local-only access).
   - Must be a `CoroutineContext.Element` with a `key` field (the
     ArticulatedNode debt from the architecture skill: the new element must
     compose into a scope, unlike ArticulatedNode).
   - The `reactorContext: CoroutineContext` parameter pattern (from the
     architecture skill: pass reactorContext so HtxKey/NioSupervisor propagate).

3. Define the MCP resource model:
   ```
   typealias McpResource = Join<String, Join<String, ContentId>>
   // uri j (description j contentCid)
   ```
   Each memory file is an MCP resource: the URI is the memory path
   (/memories/people/alice.md), the description is the frontmatter
   description, the content is resolved by ContentId through the CAS store
   (Prong 1).

4. Define the MCP tool surface for the search agent:
   - `search(query: String) -> McpSearchResult` where `McpSearchResult =
     Join<String, Series<McpResource>>` (answer j citations). This is the
     paper's (a, Gamma) contract.
   - The search dispatches to the ISAM routes (Prong 2): tries the Taxonomy
     route first, falls back to Temporal, then Membership.
   - `read_resource(uri: String) -> String` returns the full content of a
     memory file by URI.
   - `list_resources() -> Series<McpResource>` returns all memory files with
     their descriptions (the paper's "directory listing" surface).

5. Implement the MCP JSON-RPC handler:
   - `tools/list` returns the read tool set from Prong 3
     (MEMORY_READ_TOOLS).
   - `tools/call` dispatches to the MemoryToolDispatcher.
   - `resources/list` returns the memory file listing.
   - `resources/read` returns file content by URI.
   - All read-only; no write operations exposed (the paper's "search is
     read-only in intent" contract).

6. Register the MCP server routes through the LitebikeListenerElement pattern.
   Route table:
   ```
   POST /mcp/v1/tools/list    -> tool listing
   POST /mcp/v1/tools/call    -> tool dispatch
   GET  /mcp/v1/resources     -> resource listing
   GET  /mcp/v1/resources/:id -> resource content
   ```

7. Wire the search results as blackboard rows: each MCP search query emits a
   `BlackboardSurfaceRow` with lane="mcp", facet="search",
   provenance=<client agent id>, causalKey=<query hash>. The citation set
   Gamma is recorded as the provenance chain.

8. Verify: write `McpServerElementTest` under
   `src/commonTest/kotlin/borg/trikeshed/mcp/` that:
   - Starts the server element.
   - Lists resources (asserts non-empty if memory files exist).
   - Calls search with a query, asserts the result has an answer string and
     a non-empty citation set.
   - Calls read_resource with a cited URI, asserts content is returned.

## BUILD GATE

```
./gradlew jvmMainClasses --console=plain
```

## TERMINAL SURFACE

MCP resource listing and search-with-citations observable through the kanban
HTTP server route. External MCP clients (Claude Code, Codex) can connect and
query the memory store. The citation-backed search results appear in the forge
blackboard camera as cards with provenance linkage to the querying agent.

## FAN-IN NOTES

This prong owns the external API layer. It consumes the ISAM routes (Prong 2)
for search, the read tool set (Prong 3) for tool listing, and the CAS store
(Prong 1) for resource content resolution. It does not modify any of those
layers. It is the fan-in convergence point: all five prongs meet here because
the MCP server is the unified query interface over the complete memory system.
