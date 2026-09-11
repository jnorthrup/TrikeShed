# Hermetic CAS and public IPNS

Kubo remains excised. TrikeShed signs IPNS V2 records and publishes/resolves them directly through authenticated libp2p TCP/TLS/yamux and the public Amino DHT. Oroboros owns the persistent publisher and exposes its operations on the existing HTTP listener. Protocol work stays inside TrikeShed. See [native IPNS](native-ipns.md) for commands, lifecycle and limitations.

`IpfsBridge.bindAlias/removeAlias/resolveAlias` manage process-local MemoryBridge names. Its typed suspend IPNS methods publish a present CAS block through `IpnsPublisher` from the coroutine context and resolve `IpnsName` through `IpnsDht`. `IpnsNode.request` supplies these elements to callers. Public names derive from persistent signing identities; arbitrary local strings such as `memory:...` are not public identities. Public records expire; removing a local alias cannot revoke copies already on the DHT.

The active Couch `/api/v0/block/get` and `/api/v0/block/put` aliases still serve local CAS through TrikeShed's router. Their `sha256:<hex>` ContentIds are internal. Explicit CIDv1 conversion supplies the block codec and multihash; signing a pointer does not make its target available over Bitswap or advertise a block provider.

Inside TrikeShed, network operations and journal open/read/write/sync/close use the commonMain userspace uring contract. JVM cryptographic codecs only transform bytes and mint/validate in-memory certificates. The external Camel/JDBC benchmark exceptions remain as authorized.
