# Local CAS without Kubo

The unused `IpfsAdapter` / `HtxIpfsAdapter` API and its mocked network test were removed. No production call site used them. The adapter implicitly targeted a Kubo daemon at loopback port 5001, silently changed to local behavior when HTX was absent, and returned success for remote unpublication without performing an operation.

Existing functionality remains in `IpfsBridge` and the supplied `CasStore`: block put/get, process-local name replacement/resolution/removal, and MemoryBridge's local names for document spines. Existing tests now check replacement, actual removal, repeated removal, and registry separation across bridge instances sharing a CAS. No substitute network adapter was introduced.

The active Couch `/api/v0/block/get` and `/api/v0/block/put` routes remain IPFS-shaped aliases of the local CAS, served by TrikeShed's own router. They are not an outgoing Kubo RPC client and do not require a daemon. Their accepted `sha256:<hex>` identifiers are TrikeShed ContentIds; they do not establish full CIDv1/IPLD interoperability.

Native signed IPNS records, libp2p publication and DHT routing are not supplied by this local registry. Those are explicit remaining implementation gaps. Inside TrikeShed, real file and network work must use the existing commonMain userspace NIO/uring contract. The separately authorized external Camel/JDBC benchmark uses ordinary alien-library runtime I/O and does not establish that core contract. Certificate issuance is unrelated to this removal; no certificate was needed or minted.
