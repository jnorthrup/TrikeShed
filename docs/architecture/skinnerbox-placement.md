# Skinnerbox placement decisions

These decisions implement T16–T20 of the successor in TrikeShed. Requirements
sources are Skinnerbox `main` at `bd82134f`, `twins-prod-20261022` at `c0bc7602`,
and `rental-twin-pickyourai` at `e96ab339`.

| Decision | Source requirement and implementation consequence |
| --- | --- |
| P01 | Old custody requires three distinct enrolled identities and failure domains. A plan contains one executor and a `Twin` of two custodians. Generic work executes once; three receipts are custody, not three executions or consensus. |
| P02 | A caller's authorized subnet and country policy are hard constraints. `Subnet.contains` means segment-prefix containment; sibling names never authorize each other. The existing subnet embedding bounds discovery candidates and prohibits remote fanout from local scopes. |
| P03 | Kademlia proximity is unsigned numeric XOR, not Hamming population count. Routing preserves full 256-bit peer identity and orders candidates by that distance before applying its bounded candidate limit. |
| P04 | The rental twin preserves integer fractions and explicit provider units. Transfer prices bind bytes per unit, so decimal GB and binary GiB remain different. Storage prices bind both bytes and seconds per unit. Exact checked rational costs determine ranking; rounding is presentation only. Arithmetic that cannot be represented is rejected instead of wrapping or choosing an understated offer. |
| P05 | Every nonlocal directed transfer requires an explicit fresh quote naming its evidence Twin. Missing and expired prices are never zero. A zero-cost local edge means identical peer identities; a shared country/provider label does not imply free transit. |
| P06 | The cost graph has one request ingress, two replication edges, two custody receipt edges, and one response egress. Storage is charged at all three custodians; processing and retention work have distinct explicit workload units. Workload sizes are forecasts of wire bytes, including envelope overhead, not invoice evidence. |
| P07 | Enumerate every executor and unordered custodian pair in the admitted candidate set. The result is an exact minimum within that bounded, priced, authorized set and linear tariff model. It is not a claim about every Internet host, provider block billing, eventual invoices, or measured latency. Equal costs use peer identities for deterministic ties. |
| P08 | Choose and pin a plan per namespace/membership epoch. Per-row price changes must not switch the writer. Expiry requires refreshed pricing; membership or writer migration requires an explicit new epoch in the composing owner. |
| P09 | Unknown hardware can remain a planning observation, as the source twin requires. Actual placement additionally requires explicit execution qualification. Source strength and a timestamp alone do not prove capacity, physical failure independence, or permission. |
| P10 | Short work strokes bound request counts and ordinary byte quanta. The transport yields before and after a single indivisible frame larger than the observed-cache quantum; its hard frame and total admission byte limits remain enforced. Observed topology can lower a scheduling budget, and unknown topology stays explicit. This does not pin threads, promise cache residency, or prove absence of L2 misses. Deterministic per-peer/key retry jitter spreads retries without synchronized global timers. |
| P11 | The final plan captures its decision as canonical CBOR before an owner pins it: source identity, routing task, candidate hosts and exact tariffs, relevant directed transfer quotes, workload, authority/countries, exclusions with Twin references, selection/tie policy, exact costs and validity. The record is immutable and bounded to 64 KiB; excess input is refused rather than silently truncating the audit. |

The selector optimizes provider cost. The source's 1.33 customer price is a
separate derived quote; it does not change internal hosting decisions or perform
billing, settlement, provisioning, or deployment.

`MeshHost.fromTwin` reads a `host/<peer-id>` observation with `country_code`,
`valid_until_ms`, `processing_micro_usd_per_unit`, `storage_micro_usd`,
`storage_bytes_per_unit`, `storage_seconds_per_unit`, and `qualified` fields.
`MeshLinkQuote.fromTwin` reads `transfer/<source-id>/<target-id>` with `source`,
`target`, `valid_until_ms`, and separate `ingress_micro_usd`,
`ingress_bytes_per_unit`, `egress_micro_usd`, `egress_bytes_per_unit` fields.
All amounts and timestamps must be exact nonnegative signed 64-bit integers;
unit denominators must be positive. These adapters do not authenticate a Twin.
The composing owner must obtain observations through the grant-authorized store
and resolve their geographic/hardware references before admitting execution.

The `skinnerbox-placement/v1` decision represents peer endpoints once: index 0
is the source, candidate position `i` is index `i+1`; a candidate identical to the
source uses index 0 for links and selection. Each host row carries its complete
peer metadata, country, Twin CID, qualification, quote times, processing rate and
storage rate/units. Each link row carries source/target indices, Twin CID, quote
times and separate ingress/egress amounts/units. CIDs use their 32 digest bytes.
Cost rows are processing, storage, ingress and egress, each an exact
`[numerator, denominator]` in micro-USD. The recorded shortlist and quotes suffice
to rerun the constrained optimization; excluded hosts' immutable Twin references
identify the retained evidence needed to audit admission and discovery bounds.
