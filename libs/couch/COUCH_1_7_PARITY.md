# CouchDB 1.6/1.7 Parity & Endgame Usecases

## Goal
The `libs/couch` module aims to provide CouchDB 1.6/1.7 parity at the API, view engine, and document store layers. It achieves this while modernizing the infrastructure by moving away from legacy Erlang runtimes to high-performance polyglot environments (GraalVM) and decentralized storage architectures (IPFS).

## Current Featureset (vs CouchDB 1.6/1.7)

### 1. Document Store & Revisions
*   **CouchDB 1.6/1.7:** Uses B-tree append-only storage with `_id` and `_rev` for MVCC (Multi-Version Concurrency Control).
*   **`libs/couch` Parity:** Implements `ConfixDocStore` using `_id` and `_rev` semantics. It handles conflict detection based on revision mismatches and supports multiple revision generation policies (UUID, Timestamp, Sequential).

### 2. Map/Reduce Views (ViewServer)
*   **CouchDB 1.6/1.7:** Uses an external process (usually SpiderMonkey) communicating via stdio JSON protocol to evaluate Javascript `map` and `reduce` functions.
*   **`libs/couch` Parity:** Evaluates views using `org.graalvm.polyglot.Context` directly in-process via `GraalVmViewServer`. It natively bindings the Kotlin `ConfixDocStoreEntry` to JavaScript execution contexts, allowing the `emit(key, value)` function to pipe results directly back into the JVM `ViewIndex` without JSON serialization overhead.

### 3. API Surface & RelaxFactory Parity
*   **CouchDB 1.6/1.7:** Exposes a robust REST API for `_all_docs`, `_design/docs`, and view queries with standard query parameters (`startkey`, `endkey`, `descending`, `limit`).
*   **`libs/couch` Parity:** Exposes `ViewServer` interface handling compatible requests. Furthermore, it supports the `RelaxFactory` style Kotlin annotations (`@View`, `@Key`, `@StartKey`) to statically compile service interfaces into design document representations and structured queries.

### 4. Wire Protocol
*   **CouchDB 1.6/1.7:** HTTP/1.1 API.
*   **`libs/couch` Parity:** Plugs into the TrikeShed reactor framework using the unified `HTX` block protocol and `ReactorSupervisor`, allowing transport over raw NIO or NG-SCTP.

## Endgame Usecases

### A. High-Performance GraalVM ECMA View Execution
By replacing the legacy SpiderMonkey IPC view server with GraalVM Polyglot Javascript evaluation, the view building process is dramatically accelerated. `GraalVmViewServer` injects an `emit` callback directly into the JS environment. When JS code executes `emit(key, doc)`, the JVM directly catches the arguments and indexes them in `ViewStore`. This allows complex data projection without the overhead of inter-process communication.

### B. IPFS Mesh Content Addressing Storage
In a decentralized deployment, the traditional CouchDB local storage engine limits horizontal, trustless replication. By backing the `ConfixDocStore` with an IPFS-compatible content addressing layer (`IpfsMeshStore`), documents are inherently immutable and addressed by their SHA-256 hash (CID).
1.  **Immutability:** When a document is inserted, its CID is calculated.
2.  **Replication:** CouchDB replication becomes a simple matter of pinning CIDs across the mesh network.
3.  **Security:** Content addressing ensures that the data requested is cryptographically verified to be the data received, eliminating middleman attacks during replication.

This document describes the foundational layers being wired together in this project phase: a GraalVM ECMA ViewServer querying an IPFS Content-Addressed Document Store via standard CouchDB 1.6/1.7 design patterns.
