# Copilot instructions for jnorthrup/TrikeShed

Purpose
-------
Short, actionable guidance for Copilot CLI sessions operating on this repository: how to build and test, high-level architecture reminders, and project-specific agent rules derived from past sessions.

Quick commands
--------------
- Use the Gradle wrapper from repo root.
- Run full test suite (console-friendly):
  ./gradlew test --no-daemon --console=plain
- Run a single module's tests:
  ./gradlew :libs:integration-scratch:test --no-daemon --console=plain
- Run a single test class or method (use module path when needed):
  ./gradlew :libs:integration-scratch:test --tests "borg.trikeshed.integration.BinanceKlineTddTest" --no-daemon --console=plain
  ./gradlew :libs:integration-scratch:test --tests "borg.trikeshed.integration.BinanceKlineTddTest.testName" --no-daemon --console=plain
- Verbose diagnostics (only when needed):
  ./gradlew test --no-daemon --console=plain --info --stacktrace
- Build / assemble artifacts:
  ./gradlew assemble
- Test reports:
  module/build/reports/tests/test/index.html
  module/build/test-results/test/*.xml

Single-test workflow (recommended)
---------------------------------
1. Run ./gradlew test. If failures occur, inspect the *bottom-most* failing test in console output or test XML.
2. Re-run only that test with --tests (module: include module path) to iterate quickly.
3. Make a surgical code change + small focused test if needed.
4. Re-run until green. Do NOT call task_complete until verification steps pass.

High-level architecture (short)
-------------------------------
- Core algebra: prefer composition (Join, Series, Twin) over inheritance. RowVec / Cursor / MiniCursor are primary data shapes.
- Kline pipeline: CSV ZIP → parseCsv → List<Kline> → KlineCollector → sealed KlineBlock → asCursor() → MiniCursor.
- Userspace concurrency: supervised draw-through fanout (SupervisorJob.slot gives live, draw-through views; lifecycles are forward-only).
- Modules of interest: libs/dreamer-kmm, libs/dreamer-test-runner, libs/integration-scratch, libs/couch, libs/miniduck.

Key conventions and coding rules
-------------------------------
- Minimal, surgical changes only: add the smallest test that pins desired behavior then change implementation.
- Avoid broad refactors. Touch only what is required by the task.
- Do not delete files without explicit approval.
- Prefer lazy, sealed-block flows; materialize only when necessary.
- Tests that depend on external network data should be treated as integration tests or use injected fetchers/fixtures.

Agent-specific rules (for Copilot sessions)
------------------------------------------
These are actionable rules for the assistant to follow when editing, debugging, or testing this repo:

- Autonomous test-fix workflow: Run tests, pick the bottom-most failing test, re-run that test with --tests, make a minimal fix, and re-run until green.

- Delay task_complete: Only call task_complete after verifying the requested change compiles and relevant tests pass locally (or CI green if requested).

- Prefer narrow diagnostics: Use --info/--stacktrace only for deeper investigation. Focus on the failing test, not the entire log.

- Networked tests / flakiness: If tests perform network I/O (e.g., Binance CSV fetch), prefer one of these strategies:
  - Inject a test csvFetcher or provide fixture ZIPs under module/src/test/resources and run tests against fixtures.
  - Mark remote-dependent tests as integration and avoid running them during quick TDD cycles.
  - When fixing tests, add fixtures rather than enabling live network calls.

- Quick-run examples (copyable):
  - Run integration-scratch tests:
    ./gradlew :libs:integration-scratch:test --no-daemon --console=plain
  - Run single failing class:
    ./gradlew :libs:integration-scratch:test --tests "borg.trikeshed.integration.BinanceKlineTddTest" --no-daemon --console=plain

Session-history derived notes
-----------------------------
- Past runs show the assistant sometimes asked for directions instead of taking safe autonomous steps; prefer the established single-test iteration pattern.
- There are existing network-backed warnings in libs/integration-scratch tests (Binance 404s). Prefer fixture-based testing.
- The agent previously called task_complete prematurely; enforce the "verify then complete" rule above.

If you want changes to this guidance or want an MCP server section (e.g., Playwright), say so and specify the tool to add.

Created-by: Copilot CLI
