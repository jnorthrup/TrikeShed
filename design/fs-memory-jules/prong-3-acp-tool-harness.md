# Prong 3: ACP Memory Tool Harness + ModelMux Routing

## CONTEXT

Paper 2607.26637v1, RQ5: "the harness is a lever, not a neutral wrapper.
Adding a tool changes behavior, replacing the tool set reshapes the store."
The paper varies three tool sets (Center, Center+BM25, Shell) and finds that
the same model + same content produces radically different store shapes
depending on which tools are available.

The paper's formalization (Section 2.2): agents never manipulate M directly;
every access passes through a tool harness H, a finite set of operations
o: (M, args) -> (M', omega) returning a text observation omega and possibly
mutating the store.

TrikeShed already has the ACP protocol and ModelMux routing:

- `src/commonMain/kotlin/modelmux/acp/AcpProtocol.kt` — `AcpTool =
  Join<String, String>` (name j parameter-schema-JSON). The `AcpRequestBody`
  already carries `Series<AcpTool>`. The codec `encodeRequest` already
  serializes tools into the OpenAI function-calling format.
- `src/commonMain/kotlin/modelmux/ModelMux.kt` — `route(action: AcpAction,
  vararg requiredCaps)` selects a model by capability. `chat(modelId,
  messages, tools, assessmentId)` passes the tool set to the provider.
- `ModelResponseReceipt` tracks provenance (modelId, providerId, requestHash,
  tokens, latency, cachedHit).
- `src/commonMain/kotlin/borg/trikeshed/kanban/ForgeKanbanDaemon.kt` already
  imports `modelmux.acp.*`.

RQ3 finding: "Reading, not writing, is where backbone strength pays" on
conversation. Management strength buys style not quality. ModelMux.route can
select different models for management vs search roles.

## TASK

1. Define the paper's tool vocabularies as `AcpTool` constant sets in a new
  file `src/commonMain/kotlin/modelmux/acp/MemoryTools.kt`:

   Write set (management agent, paper Section C.4):
   ```
   MEMORY_WRITE_TOOLS: Series<AcpTool> = seriesOf(
     "view" j viewSchema,        // path -> content + frontmatter
     "create" j createSchema,    // path, description, content -> ack
     "str_replace" j replaceSchema, // path, old, new -> ack
     "insert" j insertSchema,    // path, line, content -> ack
     "delete" j deleteSchema,    // path -> ack
     "rename" j renameSchema,    // old_path, new_path -> ack
     "grep" j grepSchema,        // pattern, path? -> matches
   )
   ```

   Read set (search agent, paper Section C.4):
   ```
   MEMORY_READ_TOOLS: Series<AcpTool> = seriesOf(
     "view" j viewSchema,
     "grep" j grepSchema,
     "toc" j tocSchema,          // path? -> heading tree
     "section_read" j sectionSchema, // path, heading -> section content
   )
   ```

   Ranked search variant (Center+BM25, paper Section 3):
   ```
   MEMORY_READ_TOOLS_BM25: MEMORY_READ_TOOLS + ranked_search tool
   ```

2. Each tool's parameter schema is a JSON string matching the OpenAI
   function-calling format. The `description` field in each schema must state
   the paper's contract (e.g., str_replace: "Replace old_string with
   new_string in the file at path. old_string must be unique in the file.")

3. Add `harnessProfile: MemoryHarnessProfile` to `ModelMuxBuilder` where
  `MemoryHarnessProfile` is a sealed class: `Center`, `CenterPlusBM25`,
   `Shell`. The profile selects which tool set is passed to `chat()`.

4. Extend `ModelMux.route` to accept a role parameter:
  `route(role: MemoryRole, ...)` where `MemoryRole` is `Management` or
   `Search` or `Execution`. The role determines:
   - Which tool set is selected (write vs read).
   - Which capabilities are required (management needs strong reasoning;
     search needs citation; execution needs code).
   - Which model tier is preferred (RQ3: search strength pays directly,
     management buys style).

5. Wire the tool dispatch: when the provider returns a tool call, the reactor
  resolves it against the BTRFS-backed MemoryStore (Prong 1) and returns the
  observation omega. The tool resolution lives in a `MemoryToolDispatcher`
  that maps tool name to CasStore/CouchStore operation.

6. Record the selected harness profile in `ModelResponseReceipt` as a new
  field `harnessProfile: String` so RQ5-style experiments (same model,
  different tools, different store shapes) are measurable from the provenance
  trail.

7. Verify: write `MemoryToolsTest` under `src/commonTest/kotlin/modelmux/`
   that asserts each tool set has the correct tool names and that
   `encodeRequest` produces valid JSON containing the tool definitions.

## BUILD GATE

```
./gradlew jvmMainClasses --console=plain
```

## TERMINAL SURFACE

Tool-set selection observable in the ModelResponseReceipt provenance trail.
Different harness profiles produce different store shapes, measurable as
blackboard card distributions (file count, section count, cross-reference
count per the paper's Table 4 metrics).

## FAN-IN NOTES

This prong owns the protocol + routing layer. It defines tool vocabularies
that Prong 5 (MCP server) will expose externally. It dispatches to Prong 1
(MemoryStore) and Prong 2 (ISAM routes) but does not modify them. Patches
commute.
