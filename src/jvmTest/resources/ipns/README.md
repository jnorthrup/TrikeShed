# IPNS interoperability fixtures

The six binary records are unmodified files from
[ipfs/gateway-conformance v0.13.2](https://github.com/ipfs/gateway-conformance/tree/v0.13.2/fixtures/ipns_records),
specified by [IPIP-0428](https://specs.ipfs.tech/ipips/ipip-0428/).
The filename before `_` is the record's IPNS name. `SHA256SUMS` pins the downloaded bytes.
The upstream license statement and MIT/Apache-2.0 license texts accompany these files.

`IpnsRecordTest.officialIpip0428Fixtures` verifies the fixtures at 2026-09-11 UTC:

| Filename suffix | Result |
|---|---|
| `v1` | Reject: V2 is absent |
| `v1-v2` | Accept |
| `v1-v2-broken-v1-value` | Reject: legacy value disagrees with signed data |
| `v1-v2-broken-signature-v2` | Reject |
| `v1-v2-broken-signature-v1` | Accept: V1 signatures are obsolete |
| `v2` | Accept |

The fixture names and bytes are upstream test data. No Kubo binary, RPC service, or runtime dependency is used.
