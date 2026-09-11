# Native IPNS publication

TrikeShed owns the Ed25519 signing identity, IPNS V2 record, libp2p transport, iterative DHT lookup, publication, resolution, and republication. Kubo and delegated HTTP routing are absent. Protocol and publisher code lives in `commonMain/borg/trikeshed/ipns`; JVM supplies existing BC Ed25519 and the byte-only SSLEngine codec. TCP operations pass through the common uring facade and its bounded dispatcher. This is a JVM implementation of the common contract, including socket emulation beneath the facade; Kotlin/Native and JS cryptographic providers are not supplied by this change.

## Run

From the repository root:

```sh
./gradlew ipns -PipnsArgs='init --state /absolute/path/identity.journal'
./gradlew ipns -PipnsArgs='publish --state /absolute/path/identity.journal --value /ipfs/REAL_CID'
./gradlew ipns -PipnsArgs='resolve --name PUBLIC_IPNS_NAME'
./gradlew ipns -PipnsArgs='republish --state /absolute/path/identity.journal'
./gradlew ipns -PipnsArgs='serve --state /absolute/path/identity.journal'
```

The parent directory must exist. A journal creates a new identity once; restart preserves both key and sequence. `publish` changes the path, `republish` sends the current signed record again, and `serve` periodically republishes and renews it. `serve --value /ipfs/CID` publishes before entering the loop. Output is JSON. The CLI exits with status 2 for incomplete replication or insufficient resolver quorum and 1 for an execution error; it retains peer failures instead of reporting local assignment as network success.

## Daemon

Oroboros owns an `IpnsNode` for its entire lifetime. Its signing journal is
`<forgeHome>/.oroboros/ipns.journal`; restart restores that identity and resumes
republication. `TRIKESHED_IPNS_BOOTSTRAP` replaces the default numeric bootstrap
addresses. The existing HTTP listener exposes these operations:

| Request | Result |
|---|---|
| `GET /api/v0/name/status` | Name, lifecycle, latest record, last publication report and failure. |
| `POST /api/v0/name/publish?arg=/ipfs/CID` | Durable signed update followed by public DHT publication. `/ipns/NAME` values are also accepted. |
| `POST /api/v0/name/republish` | Publish the current record again. |
| `GET /api/v0/name/resolve?arg=NAME` | Fresh DHT lookup and validation; `POST` is also accepted. |

Publication returns 200 only after the replica target is reached; incomplete
replication returns 503 with actual counts and peer failures. Resolution returns
200 with `Path` only after quorum, 404 for established absence, or 503 with an
explicit candidate and incomplete-quorum details. Invalid input returns 400.

The commonMain node dispatches requests through a bounded channel under its
owning supervisor. Accepted work finishes if its HTTP client disconnects. Drain
stops admission, joins requests, drains the publisher, then closes the transport
and journal. This cleanup also runs on daemon boot failure and early return.
In-process callers use `node.request { ... }`, which supplies the typed publisher,
DHT and crypto elements to public `IpfsBridge` operations.

MemoryBridge's `bindAlias`, `removeAlias` and `resolveAlias` describe its local
memory labels. Public records are created through the explicit publication
operation above; reconciling memory documents does not assign them all to the
daemon's single signing name.

Default lifetime is 48 hours, cache TTL 5 minutes, republication interval 4 hours, and failed/partial-publication retry 5 minutes. Options `--lifetime`, `--ttl`, `--republish-interval`, `--retry-interval`, and optional bounded `--duration` are seconds; `--rpc-timeout` and `--timeout` are milliseconds. A restarted service promptly republishes its recovered record. Changed values increment sequence; same-value renewal preserves sequence and advances signed EOL even after a backward clock adjustment. TTL never extends EOL. A durable record is written and synced before network PUTs.

Journal creation uses owner read/write permissions. An exclusively created `.lock` lease prevents two processes opening the same journal path. Orderly close drains admitted publication work and removes the lease; a crash may leave it behind. Remove that specific lease only after establishing its writer has stopped. Do not use path aliases to open one journal twice. Torn trailing records are truncated on recovery; corrupt complete records and damaged identities are rejected, never silently replaced.

## Bootstrap and routing

The CLI defaults to the numeric TCP addresses of four official Amino bootstrappers, resolved on 2026-09-11. Supply `--bootstrap /ip4/.../tcp/.../p2p/PEER_ID,...` to replace that snapshot. Numeric IPv4 and IPv6 addresses, peer identity matching, bounded address counts and local/private-address filtering are enforced. DNS/DNSADDR expansion, QUIC, WebSocket, Noise, and listening/provider/Bitswap service are outside this client implementation. Bootstrap addresses can change; explicit configuration replaces them without code changes. The VA1 snapshot is retained as an optional constant but its failed handshake is not hidden or made a default requirement.

Routing uses SHA256 of the binary peer ID and SHA256 of the raw `/ipns/` routing key, ordered by the full unsigned XOR distance. It does not reuse the internal overlay's Hamming-distance lookup. The client searches iteratively, writes signed records using PUT_VALUE and requires exact returned record/key bytes for each acknowledgement. Resolution performs a fresh GET_VALUE traversal, validates each candidate, collects the configured quorum (default 16), and selects sequence/EOL/unsigned-wire ordering. Newer minority responses win; stale closest peers receive read repair. Invalid peer advertisements cannot invalidate a valid returned record.

Publication attempts the closest set first. Failed storage slots can be filled by other authenticated peers already queried during that lookup, in distance order and within the PUT attempt limit. Reports separate primary and replacement acknowledgements and retain all failures. A failed peer never counts as a replica.

TLS authenticates Ed25519 or RSA host identities using the signed libp2p certificate extension and expected peer ID. It requires TLS1.3 and ALPN `libp2p`, sends no SNI, rejects unknown critical certificate extensions, and renews self-issued in-memory certificates before expiry. Each RPC owns one yamux stream and connection. Admission, timeouts, fan-in, CLOSE and drain are explicit. Close notification failure is retained in the I/O trace without invalidating a complete authenticated response; descriptor cleanup remains mandatory.

JVM certificate parsing can reject otherwise reachable peers, including observed certificates with empty issuer DNs. Such failures remain in the report; certificate and peer identity checks are not relaxed.

## Trace sleeve

`--trace /absolute/path/io.jsonl` enables the reusable `UringTrace` context element at actual backend admission/completion. It records channel/request identity, opcode, descriptor, width, offset, timing, availability and actual results. Negative nonblocking retry results remain visible. Backend exceptions are separate failure observations, not fabricated CQEs. Payload bytes and signing keys are not logged. The bounded trace reserves room for paired completions and reports omitted/unpaired events. Absence of the context element leaves the backend undecorated.

This exposes a place to observe and compare I/O adaptation while retaining the existing submission contract. It does not claim live descriptor migration, native execution for JVM socket descriptors, or benchmark latency improvements. Existing polyglot and ISAM benchmark artifacts remain unchanged.

## Verification

Run the in-tree checks with:

```sh
./gradlew jvmTest --tests 'borg.trikeshed.ipns.*' --tests 'borg.trikeshed.cas.IpfsBridgeTest' --tests 'borg.trikeshed.userspace.UringConformanceTest'
```

Set `TRIKESHED_IPNS_TEST_JOURNAL` to an existing public-test journal to include
the live daemon HTTP check. It publishes the journal's current path with a renewed
record, republishes it, and resolves it through the public DHT. Without that
explicit journal, the public-network check is skipped.

The daemon HTTP check on 2026-09-11 returned 200 for publication and republication,
each with 20 acknowledged peers (18 primary, 2 replacements). A fresh HTTP
resolution returned the same sequence, path and EOL from 18 valid responses,
exceeding quorum 16. The node drained and released its journal lease.

The 2026-09-11 TrikeShed runtime run obtained 20 validated public PUT acknowledgements and a fresh TrikeShed resolver obtained 20 valid responses for quorum16, selecting sequence1 over a stale sequence0 response. The publisher's signed wire and the resolver's selected wire matched. A periodic local run completed five publications and drained all ten observed transport descriptors. These are historical observations; signed records expire at EOL. Run `publish` and `resolve` with `--trace` to capture current behavior. Official IPNS fixtures and API/codec details are in [IPNS records](ipns-records.md).

Primary protocol references: [IPNS records](https://specs.ipfs.tech/ipns/ipns-record/), [Amino Kademlia DHT](https://specs.ipfs.tech/routing/kad-dht/), [libp2p TLS](https://github.com/libp2p/specs/blob/master/tls/tls.md), [yamux](https://github.com/libp2p/specs/blob/master/yamux/README.md), and [official bootstrappers](https://docs.ipfs.tech/concepts/public-utilities/#amino-dht-bootstrappers).
