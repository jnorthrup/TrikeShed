/**
 * literbike CCEK Assemblies & Modules — Stub Index
 *
 * This file marks the literbike module root. Each subdirectory below corresponds
 * to a Rust source module in /Users/jim/work/literbike/src/. Porting proceeds
 * module-by-module using TDD (failing test → implementation → green).
 *
 * CCEK Assemblies (port with CCEK Key/Element integration):
 * - ccek/htxke: X25519/HKDF hourly access tickets (+/- 1hr tolerance)
 * - ccek/agent8888: AI agent gateway, element stubs, request factory
 * - ccek/api_translation: Multi-provider API normalization (OpenAI/Anthropic/Gemini/DeepSeek)
 * - ccek/http: HTTP server, header parser, session management
 * - ccek/json: Custom JSON parser with bitmap indexing, object pooling
 * - ccek/keymux: Model key multiplexer, DSEL routing, token tracking
 * - ccek/quic: Full QUIC protocol, TLS crypto, session cache, Bedrock, WAM
 * - ccek/sctp: SCTP associations, chunks, streams, sockets
 * - ccek/store: CAS gateway, backends (memory/git/IPFS/S3/RocksDB), CouchDB, git sync
 *
 * Non-CCEK Modules:
 * - betanet, channel, concurrency, couchdb, curl_h2, dht, endgame, gates
 * - http_htx, htx, htxke, json, modelmux, protocol, quic
 * - rbcurse, rbcursive, reactor, request_factory, router, session
 * - simd, trikeshed, types, uring, adapters
 */
package borg.literbike
