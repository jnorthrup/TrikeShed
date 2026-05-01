# libs/combined-client — boundary audit & path to stable

## Boundary audit

### Clean boundaries
- [x] CombinedClientElement cascades open() to all children and reverses on close()
- [x] executeRpc() requires OPEN state before dispatch
- [x] CombinedClientApp uses supervisorScope for fault isolation in the RPC loop
- [x] ReactorElement is clearly marked as a placeholder

### Boundary concerns
- [ ] CombinedClientElement.executeRpc() for "ipfs"/"htx" constructs an
      HtxClientRequest and calls `htx.requestHandler(request)` directly — it
      bypasses `htx.request()` (which normalises method to uppercase and
      enforces OPEN state).  This means the OPEN check is redundant (already
      done in executeRpc) but method normalisation is skipped for IPFS/HTX
      commands.  Inconsistent with the htx-client's own request() contract.
- [ ] CombinedClientElement.executeRpc() for "quic" and "sctp" returns
      hardcoded echo strings — no actual transport dispatch.  If the combined
      client is meant to be a real multi-protocol client, these stubs need
      real implementations or should be removed until the transport layer
      supports them.
- [ ] CombinedClientApp.init parses "-d" but skips the next arg naively
      (`args[i + 1]`) without checking if the next arg starts with "-" — a
      directory named "-something" would work but a missing value would grab
      the next flag as the dir path.
- [ ] CombinedClientApp.startRpcSession() uses `supervisorScope` but never
      returns — the launched coroutine blocks on `rpcChannel.consumeEach`
      until the channel is closed.  There is no timeout, no cancellation
      propagation, and no way to signal session end from outside.
- [ ] CombinedClientApp.open() calls `super.open()` then `combinedClient.open()`
      — if combinedClient.open() fails, the app element is left OPEN with a
      half-initialised child.  No rollback.
- [ ] ReactorElement.process() is a no-op that requires OPEN state.  It is
      unclear what the reactor is supposed to do.  Should either be removed
      or given a clear contract.
- [ ] No generated code (Keys, Elements, SupervisorJobs) for this module —
      if it is meant to participate in the OpenAPI pipeline, it needs
      generator integration.
- [ ] CombinedClientElement stores child elements as constructor params with
      defaults — the defaults create fresh instances, which means
      `CombinedClientElement()` creates 4 new elements that are never in any
      CoroutineContext until the combined element itself is added to one.
      The children's lifecycle is managed by the parent but they are not
      individually addressable in a CoroutineContext.

### Integration edges
- [ ] Depends on all three transport libs (quic, ngsctp, htx-client) — any
      change to their Key/Element signatures breaks this module
- [ ] HtxClientRequest with Aria2Switches is constructed here but the default
      HtxElement handler ignores switches/uris entirely — the dispatch is
      effectively dead code until a real HTTP transport handler is injected

## Path to stable

1. **Unify request dispatch** — use `htx.request()` instead of
   `htx.requestHandler()` for IPFS/HTX commands, or formalise the direct
   handler call with its own contract that includes switches/uris.
2. **Implement or remove QUIC/SCTP stubs** — the echo strings are not useful.
   Either wire them to real transport calls or raise NotImplementedError until
   the transport layer matures.
3. **Fix arg parser robustness** — handle missing -d value, unknown flags,
   and URIs properly.  Consider using a proper arg parser or at minimum
   validating that the value after -d is not another flag.
4. **Session lifecycle** — add a close signal to startRpcSession (e.g., a
   second Channel or a timeout).  Ensure cancellation propagates to children.
   Add a `closeSession()` method or use a `Job` return value.
5. **Transactional open** — wrap combinedClient.open() in try/catch within
   CombinedClientApp.open(); close the app element on child open failure.
6. **Define ReactorElement contract** — either remove it or specify what
   `process()` should do (command execution? event dispatch? scripting?).
   Add tests for it.
7. **Add generated codegen** — if this module's RPC surface is spec-driven,
   add OpenAPI generation for the combined client operations, including
   SupervisorJobs for each command.
8. **Test coverage** — only CombinedClientElementTest exists.  Need tests for:
   - CombinedClientApp arg parsing
   - CombinedClientApp startRpcSession / close session
   - ReactorElement lifecycle and process()
   - Error paths (child open failure, RPC on closed element)
9. **Element context participation** — consider whether child elements should
   be individually resolvable in a CoroutineContext, or if the combined
   element should be their sole owner.  Current design is sole-owner but
   this should be an explicit decision.
