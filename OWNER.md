Ownership map — TrikeShed

Purpose
-------
Establish canonical owners for the core algebra, MiniDuck primitives, numeric kernels, and domain layers so RED tests map to a single, discoverable implementation owner.

Canonical owners
----------------
- libs/couch (package: borg.trikeshed.couch.*)
  - Owner of the algebraic core and MiniDuck primitives: Cursor/RowVec/DocRowVec/MiniCursor, Kline types, KlineBlock, block sealing and I/O.
  - Canonical numeric kernels (borg.trikeshed.couch.finance): mean, variance, stdDev, ema, windowSum and low-level finance utilities (toPriceCursor, doubleSeries).
  - Domain-agnostic financial MiniCursor adapters.

- libs/common (package: borg.trikeshed.lib.*)
  - Shared small utilities used across modules (Series, j, size helpers, concurrency primitives).

- libs/dreamer-kmm (package: dreamer.*)
  - Owner of domain-specific trading logic: SimWallet, DreamerKernelTransformer, MuxIo/Pancake transforms, KlineMuxer/KlineStreamer, Genome/evaluator, Wallet/strategy/risk code, exchange parsers, harnesses.
  - May re-export canonical kernels from libs/couch but must NOT duplicate their implementations.

- libs/openapi, libs/server, libs/htx-client, etc.
  - Own their protocol/transport-specific functionality (OpenAPI generators, server context, clients).

- Root (project: TrikeShed)
  - Repository-level build composition, composite includeBuild guidance, integration harness, and documentation (AGENTS.md). Use root to coordinate ownership decisions.

Policies
--------
- No duplication of algebra or numeric kernels between modules. Move or re-export, do not copy.
- When introducing or moving an API, update OWNER.md and AGENTS.md with the new owner and rationale.
- RED tests in dreamer-kmm are specification artifacts: implement their required APIs in dreamer-kmm while depending on canonical primitives in libs/couch.

How to use
----------
- Before coding, consult OWNER.md to determine the target module for changes.
- Add small, minimal implementations in the domain module that call into libs/couch for algebraic & numeric operations.
- When a change crosses ownership, open a short PR that explicitly documents the ownership change and why.
