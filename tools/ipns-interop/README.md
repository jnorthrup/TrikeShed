# Independent native IPNS interoperability

The Kotlin implementation is built by the root project. This Go module supplies a separate test peer and fresh public resolver using upstream go-libp2p 0.49.0, go-libp2p-kad-dht 0.42.2 and Boxo 0.42.2. `go.mod` and `go.sum` pin the verifier. It is never invoked by production and no Kubo binary or daemon is involved.

```sh
cd tools/ipns-interop
go build -o ipns-interop .
./ipns-interop -mode serve
```

The server prints its exact peer multiaddr. In another process, publish through the root CLI with that address and `--replicas 1 --quorum 1 --allow-private true`. `-rsa` starts an RSA host identity fixture. To independently retrieve a public record:

```sh
./ipns-interop -mode get -name PUBLIC_NAME -expect /ipfs/EXPECTED_CID
```

`-peers` accepts explicit comma-separated full multiaddrs; the default uses upstream public bootstrap configuration. The resolver starts with a fresh identity and empty store, waits for actual DHT routing-table admission after identify, obtains GET_VALUE, then verifies the name, signature, EOL and expected path with Boxo. It never accepts the publisher's record as input. Local fixtures also use that independent network path.

The retained `evidence/verification.json` contains exact source/artifact hashes, commands, test counts and trace pairing results. JSON protocol reports include authenticated remote identities, exact signed record bytes, replica/quorum outcomes and failures. Compressed JSONL contains actual common-uring backend requests/completions; neither decrypted payloads nor private keys are recorded. Public payload files are ordinary deterministic test content; IPNS pointer publication does not imply Bitswap availability.

The public test identity is `k51qzi5uqu5dik63g2ipi483m2ee8yr1055si1irk3x8w3ymscxhc67zb83j7o`. Version 0 points to `bafkreif4d6qvlsekbgcwh3ndnfhzc2crwzdfs7pakuzk3qgtxyj52ay4qi`; version 1 changes it to `bafkreigywvdgfojeu7okqznfdwtumy4e62oxycj4izml77bryzoopxnwgm`. Separate fresh Go processes independently resolved both; a fresh native resolver obtained 18 valid responses for quorum16 and selected version1 despite two version0 responses.

The initial public runs obtained 19 of20 primary acknowledgements and correctly reported partial replication. One routing peer reset storage streams. Final code attempts the original closest set first and can fill failed slots from other authenticated, already queried peers in distance order, under a separate PUT attempt bound. Reports distinguish those replacements. Default bootstrap addresses are an explicit 2026-09-11 DNS snapshot of official am6/sg1/ny5/sv15 nodes; operator configuration can replace them. Failed VA1 and early shutdown attempts are retained as failed evidence, without a public-success claim.

The final build obtained 20 acknowledgements from 22 PUT attempts: 18 primary and 2 replacements. Its fresh native resolver collected 20 valid responses for quorum16 (19 at sequence1, one at sequence0), selected sequence1, and a fresh Go resolver independently returned the identical signed bytes. The final publication trace contains 79,086 matched request/completion pairs and 58 successfully closed descriptors. A five-second local `serve` run emitted five successful republication reports, independently retrieved by Go, with 357 matched pairs and all10 descriptors closed. Neither trace omitted events. This local run verifies republication of unchanged bytes; expiry renewal and retry after operation timeout are covered by the journal/publisher tests.

The final build passes 39 selected tests across record validation, transport, DHT traversal/storage, journal lifecycle, CAS integration and the common uring contract. XML results and the compressed build log are retained under `evidence`. Python audit scripts came from the independent read-only audit and do not import TrikeShed:

```sh
python3 verify_records.py evidence/public-publish-v0.json evidence/public-go-v0.json evidence/public-final-publish.json evidence/public-final-go.json evidence/public-final-native.json --payload evidence/public-v0-cid.json evidence/public-v0-payload.bin --payload evidence/public-v1-cid.json evidence/public-v1-payload.bin
python3 audit_trace.py evidence/*.trace.jsonl.gz
```

The signature verifier requires Python `cryptography` and checks EOL against the current time. Retained reports attest to the recorded verification time; repeating that check after their EOL requires publishing a renewed record. No journal or signing seed is included in this directory.

All observed TrikeShed network traffic was JVM descriptor emulation beneath the commonMain uring facade on macOS. JNI/native capabilities are reported per backend; this evidence makes no claim of Linux kernel socket execution. See [runtime documentation](../../docs/native-ipns.md) for supported transport, cryptography, persistent journal, graceful shutdown, and republication controls.
