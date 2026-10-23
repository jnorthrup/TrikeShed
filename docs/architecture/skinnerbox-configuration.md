# Skinnerbox configuration and launch

`MeshConfiguration` is common Kotlin. `parse(bytes, localPublicKey)` loads an
explicit membership configuration; `plan(bytes, localPublicKey, nowMillis)`
selects cells using `MeshPlacement` and returns the same runtime type.
`bytes()` writes deterministic JSON with sorted object keys. Private identity
seeds and API tokens are separate deployment inputs and never appear in this
configuration.

| Decision | Consequence |
| --- | --- |
| C01: provision enrollment explicitly | A public key determines its peer ID. Duplicate keys/origins, unenrolled identities and a seed whose public key is absent are refused. The config is an operator trust input, not remotely discovered authority. |
| C02: pin the complete N=3 cell | `cells` contains three ordered peer IDs and the canonical CBOR decision as lowercase hex. The first is the writer. Parsing checks the decision's namespace, member order and enrolled peer metadata. Serving never reselects when prices or clocks change. |
| C03: require every permission | Each cell member and the local peer must have that exact namespace in `persistenceGrants`, within the peer's subnet. API/Twin namespaces must name configured cells. Missing fields and grants do not become defaults or wildcards. |
| C04: authorize pricing evidence | A draft names an enrolled author for each observation. The author's configured Twin source/key grant and persistence namespace must admit it. Host evidence must be `measured`. Observation `via` text is provenance, not authentication. Provisioned evidence is trusted to the config operator; importing a signed remote observation must happen through the authenticated Twin owner first. |
| C05: bound the entire input | JSON is at most 128 KiB, 2,048 values and 16 nesting levels. Duplicate keys, unknown fields, invalid UTF-8, ambiguous hex, fractional/out-of-range integers and oversized documents fail before runtime. Individual 64 KiB decision limits and candidate limits still apply. The whole-config limits can be tighter. |
| C06: separate provision from serve | A draft is never accepted by `parse`. `plan` evaluates fresh explicit prices at its supplied time and writes a finalized config. Preserve the draft as operator evidence; the decision retains pricing, load, Twin IDs and optimization policy. Quote expiry invalidates the forecast, not existing ownership. Changing membership requires coordinated data handoff before activating the new epoch; the CLI does not automate migration. |
| C07: keep source and tenant grants separate | `TwinGrant` governs one peer's allowed source/kind/key prefixes across its explicitly authorized Twin namespaces. It is not a per-namespace source policy. Separate enrolled identities are needed when those authorities must differ. |
| C08: bind state before serving | Runtime startup durably binds each cell epoch before Twin replay and listener admission. A conflicting persisted epoch refuses startup and releases its writer lease. Shutdown closes dependent owners in order; a component cleanup failure cancels remaining children and reports failure instead of claiming a successful drain. |
| C09: recover an explicit offline snapshot | The CLI exports a bounded manifest from retained custody packets, or restores it to a new directory using the original owner's identity and epoch. Source and destination are held under exclusive writer leases and closed on success/failure. Recovery does not elect another owner or infer that a snapshot includes all later distributed writes. |

The finalized object requires exactly these fields:

| Field | Value |
| --- | --- |
| `schema` | `skinnerbox.config/v1` |
| `peers` | 3–256 `{publicKeyHex, failureDomain, origin, subnet}` records; key hex is exactly 64 lowercase characters |
| `cells` | 1–64 `{namespace, members, decisionHex}` records; `members` is the ordered three-element peer-ID array |
| `persistenceGrants` | `{peerId, namespaces}` records; namespace grants are exact, not prefix expansion |
| `apiKeys` | `{namespace, tokenHash, write}` records; hash format is `sha256:` plus 64 lowercase hex characters; `write` is required boolean |
| `allowedOrigins` | Exact HTTPS or loopback HTTP browser origins; empty denies browser cross-origin requests |
| `twinNamespaces` | Namespaces with an open Twin store |
| `twinGrants` | `{peerId, sources, scopes}`; `sources` contains `planned`, `curated` and/or `measured`; `scopes` contains `{kind, prefix}` |

Empty `apiKeys` disables the API. `twinNamespaces` and `twinGrants` may both be
empty to disable Twin tasks. Prefixes in Twin keys end at `/`: `alice` permits
`alice` and `alice/...`, never `alice-other`. An empty prefix authorizes the
whole selected kind; `kind: "*"` is an explicit grant across kinds. These do
not widen dotted peer namespace scopes.

A draft uses `schema: "skinnerbox.plan/v1"` and replaces `cells` with
`placements`. Each placement has exactly:

* `namespace`, `verb`, `payloadHex`: a stable policy task for the epoch, not a
  particular user's row mutation. Payload bytes influence deterministic XOR
  ordering and must not contain secrets.
* `sourceId`: enrolled ingress identity used in the cost forecast.
* `hosts`: `{peerId, authorId, observation}` rows. Observations are the
  `skinnerbox.twin/v1` JSON objects described in [placement](skinnerbox-placement.md).
* `links`: `{authorId, observation}` rows with explicit directed transfer
  rates. Both directions matter; reverse prices are never inferred.
* `policy`: `{authority, countries, maxCandidates}`. Country codes and scope
  are hard authorization inputs; `maxCandidates` is 3–20.
* `load`: `{requestBytes, replicaBytes, responseBytes, receiptBytes,
  processingUnits, replicaUnits, retentionSeconds}` with exact nonnegative
  integers. Include forecast envelope overhead in byte counts.

The planning command rejects stale/unqualified/missing evidence when no valid
N=3 plan remains. Its minimum is restricted to the bounded authorized candidate
set and explicit linear tariffs. See [placement decisions](skinnerbox-placement.md)
for exact fractions, unit denominators, full request/replication/receipt/response
costs and the deterministic tie rule.

## Local three-process example

Run from the TrikeShed checkout with its configured JDK and Gradle dependencies.
This example creates fresh secrets in a private temporary directory and stores
only public enrollment and API-token hashes in JSON. Its host observations,
tariffs and failure-domain names are **synthetic fixtures**. Three loopback
processes share one physical machine and do not demonstrate independence from a
host failure. Replace the fixtures with authenticated measurements and provider
quotes before planning real hosts.

Create identities without printing seeds or tokens:

```sh
export MESH_DEMO="$(mktemp -d /tmp/trikeshed-mesh.XXXXXX)"
python3 - <<'PY'
import os
from pathlib import Path
os.umask(0o077)
p = Path(os.environ['MESH_DEMO'])
for i in range(3):
    (p / f'peer{i}.seed').write_bytes(os.urandom(32))
(p / 'api.token').write_text(os.urandom(32).hex())
PY
for i in 0 1 2; do
  ./gradlew -q -PkotlinJsApps= skinnerbox \
    --args="identity $MESH_DEMO/peer$i.seed" > "$MESH_DEMO/peer$i.json"
done
```

Construct the bounded draft from those public identities. The observations
explicitly mark their provenance as synthetic; the `measured` source label
exercises the planning contract and is not a claim about real cloud capacity.

```sh
python3 - <<'PY'
import hashlib, json, os, time
from pathlib import Path
p = Path(os.environ['MESH_DEMO'])
identities = [json.loads((p / f'peer{i}.json').read_text()) for i in range(3)]
ids = [x['peerId'] for x in identities]
namespace = 'tenant.rows'
now = time.time_ns() // 1_000_000
until = now + 3_600_000
def observation(kind, key, fields):
    return dict(twin='skinnerbox.twin/v1', kind=kind, key=key,
                source='measured', via='SYNTHETIC-LOOPBACK-FIXTURE',
                at_ms=now, fields=fields)
hosts = [dict(peerId=peer, authorId=ids[0], observation=observation('host', peer,
    dict(country_code='DE', qualified=True, valid_until_ms=until,
         processing_micro_usd_per_unit=i + 1, storage_micro_usd=1,
         storage_bytes_per_unit=1_000_000_000, storage_seconds_per_unit=3600)))
    for i, peer in enumerate(ids)]
links = [dict(authorId=ids[0], observation=observation('transfer', f'{a}/{b}',
    dict(source=a, target=b, valid_until_ms=until,
         ingress_micro_usd=1, ingress_bytes_per_unit=1_000_000_000,
         egress_micro_usd=2, egress_bytes_per_unit=1_000_000_000)))
    for a in ids for b in ids if a != b]
draft = dict(
    schema='skinnerbox.plan/v1',
    peers=[dict(publicKeyHex=x['publicKey'], failureDomain=f'demo-process-{i}',
                origin=f'http://127.0.0.1:{9100+i}', subnet='tenant')
           for i, x in enumerate(identities)],
    persistenceGrants=[dict(peerId=x, namespaces=[namespace]) for x in ids],
    apiKeys=[dict(namespace=namespace, write=True, tokenHash='sha256:' +
                 hashlib.sha256((p / 'api.token').read_bytes()).hexdigest())],
    allowedOrigins=[], twinNamespaces=[namespace],
    twinGrants=[dict(peerId=x, sources=['measured'],
                     scopes=[dict(kind='host', prefix=''), dict(kind='transfer', prefix='')])
                for x in ids],
    placements=[dict(namespace=namespace, verb='epoch', payloadHex='01',
                     sourceId=ids[0], hosts=hosts, links=links,
                     policy=dict(authority='tenant', countries=['DE'], maxCandidates=3),
                     load=dict(requestBytes=1024, replicaBytes=2048, responseBytes=512,
                               receiptBytes=512, processingUnits=3, replicaUnits=1,
                               retentionSeconds=3600))])
(p / 'draft.json').write_text(json.dumps(draft, sort_keys=True, separators=(',', ':')))
PY
./gradlew -q -PkotlinJsApps= skinnerbox \
  --args="plan $MESH_DEMO/draft.json $MESH_DEMO/peer0.seed $MESH_DEMO/config.json"
```

The CLI supplies its current time to the pure planner. Planning must complete
within the fixture quote's one-hour validity. The output path must not exist;
the tool does not overwrite another epoch. All three peers use the same output
config. In three terminals, set `MESH_DEMO` to the same directory, then run the
corresponding command (one per terminal):

```sh
./gradlew -q -PkotlinJsApps= skinnerbox --args="serve $MESH_DEMO/config.json $MESH_DEMO/peer0.seed $MESH_DEMO/state0 127.0.0.1:9100"
./gradlew -q -PkotlinJsApps= skinnerbox --args="serve $MESH_DEMO/config.json $MESH_DEMO/peer1.seed $MESH_DEMO/state1 127.0.0.1:9101"
./gradlew -q -PkotlinJsApps= skinnerbox --args="serve $MESH_DEMO/config.json $MESH_DEMO/peer2.seed $MESH_DEMO/state2 127.0.0.1:9102"
```

After each listener reports ready, create and read one row. The credential stays
outside the config and source tree:

```sh
MESH_API_KEY="$(cat "$MESH_DEMO/api.token")"
curl --fail-with-body -H "apikey: $MESH_API_KEY" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-row-1' \
  -H 'Prefer: return=representation' --data-binary '{"id":"alice","name":"Alice"}' \
  http://127.0.0.1:9100/rest/v1/users
curl --fail-with-body -H "apikey: $MESH_API_KEY" \
  'http://127.0.0.1:9102/rest/v1/users?id=eq.alice'
unset MESH_API_KEY
```

All three custodians must be reachable before a mutation acknowledges N=3.
After a partial custody response, retry the same method, body and idempotency
key. Stop each foreground process with Ctrl-C; it stops admission and drains
accepted work. Restart with the same seed, config and state directory to retain
the same identity, membership epoch and data. A changed config is not an
automatic migration. See the [data API](skinnerbox-data-api.md) for revision
preconditions, objects, changes and Twin requests.

For remote hosts, enroll the externally reachable HTTPS origins and terminate
TLS at those endpoints. Explicit `--external-bind` permits a private listener
behind that deployment endpoint. The local example opens no external bind and
installs no hosted resources.

## Offline custody recovery

Stop the source process before taking its exclusive storage lease. With its
original finalized config and a configured custodian's seed, export a canonical
CBOR manifest to an absent output file:

```sh
./gradlew -q -PkotlinJsApps= skinnerbox \
  --args='snapshot /private/config.json /private/custodian.seed /private/source-state tenant.rows /private/snapshot.cbor'
```

Restore that manifest from the retained source packets to a new directory, with
an existing parent directory. The seed must belong to the original configured
writer for that namespace; the destination path must be absent:

```sh
./gradlew -q -PkotlinJsApps= skinnerbox \
  --args='restore /private/config.json /private/owner.seed /private/source-state tenant.rows /private/snapshot.cbor /private/restored-state'
```

The source state remains available. A failed destination is left for inspection;
an incomplete restore marker prevents ordinary service from treating partial
replay as complete. This command restores one namespace from an explicit
manifest and does not move membership. Review the snapshot cutoff before
restarting the original owner with the restored directory, and follow the
[recovery contract](skinnerbox-recovery.md) for completeness and missing-packet
limits. No private key or API token is printed by these commands.
