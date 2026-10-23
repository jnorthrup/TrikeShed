# Skinnerbox digital twin decisions

Requirements come from Skinnerbox `main` at
`bd82134f6fcae377ec1d2ec569bdec855245bce1`, `twins-prod-20261022` at
`c0bc7602470326fb14b6b0076c7f05dc55d6573f`, and `rental-twin-pickyourai` at
`e96ab339c7a9a5ba2f74e32369f4bb4228dea2ed`. The successor is implemented in
TrikeShed common Kotlin. The rental branch supplies provider-cost requirements;
the other two supply observation, retention, and world-model requirements.

| Task | Decision | Reason and consequence |
| --- | --- | --- |
| T01 | Immutable canonical observations use `skinnerbox.twin/v1`, SHA-256 `ContentId`, and the existing Confix index. | Rust's public mutable fields could disagree with its retained bytes. The new API exposes defensive snapshots, and exact numeric text never passes through a floating-point reification. Legacy import is an explicit conversion with its old CID retained as provenance; it does not claim byte identity across formats. |
| T02 | Fold by source strength, then earliest hardware revision / latest other observation, then CID. | Preserve the old deterministic rule without depending on arrival order. A measured revision replaces a plan; its first measured collection remains authoritative. |
| T03 | The authoritative set grows only; union preflights count, byte capacity, and grants before mutation. | A refused batch must not leave a partial merge. Winner-only compaction is a derived view, never deletion of retained observations. Savepoints use TrikeShed LineCas neighbour stamps; bounded residual pages transfer only missing content. |
| T04 | Callers supply an explicit verified-author grant; relationship validation follows retention. | A peer cannot promote itself by putting `measured` in JSON. Missing relationships must be reported without making CRDT union depend on delivery order. The transport authenticates identity; the pure fold does not invent that authentication. |
| T05 | Test permutations, bounds, hostile JSON, defensive ownership, grants, residual replay, and relationship repair. | These checks exercise divergence and authority failures that happy-path fixture splitting does not detect. |

No per-observation network call or background coroutine belongs in the pure
twin store. The owning mesh reactor serializes admission; its persistence stage
must retain bytes durably before publishing the updated view. A bounded logical
byte budget does not establish JVM object size or guarantee L2 cache residency.

## Wire and admission details

`TwinJson` retains integer precision through Confix byte spans. JSON grammar is
checked by the existing strict validator; its Double-valued reification is
discarded. The index then supplies exact spans, decoded-key uniqueness, one
root, and structural bounds (depth 16 and 2,048 values). Valid UTF-8 and paired
Unicode surrogates are required. Canonical objects sort keys; decimal numbers
normalize mathematically without allocating exponent-sized strings. Signed
64-bit timestamps cover nonnegative epoch milliseconds. Larger field integers
remain exact as `ULong` or `TwinNumber`, never a rounded `Double`.

This is an explicit successor format. `importLegacy` accepts the bounded old
`loom.twin/v1` document, converts it, and records its old byte CID in `legacy`.
Old and new observations consequently have different addresses. No Kotlin/Rust
float-format parity or automatic migration of live retention is claimed.

Every union receives a `TwinGrant` from an authenticated owner, with allowed
sources and literal hierarchical key scopes. The JSON `via` field is descriptive
provenance, not proof of identity. A grant is not read from the observation.
The caller must capture a batch once and serialize validation, durable append,
and publication; a dynamic Series oracle must not change between those steps.

Residuals are sorted by CID and carry the current LineCas savepoint, a bounded
observation count, a byte bound, and a continuation CID. A caller that pins the
savepoint across pages gets an explicit refusal if the view changed. Returning
only current fold winners would lose the append-only history and is forbidden.
The default store bounds are 8,192 observations and 16 MiB of canonical bytes;
capacity refusal does not trigger implicit compaction.

Relationship diagnostics retain the original model→family, quant→model,
hardware revision→class, rental→hardware/country, and country→continent links.
Extensible kinds can declare `references: [{kind, key}]`. An unresolved link
does not reject observation retention: doing so would make merge depend on
arrival order. Callers must use these diagnostics when deciding whether a
derived operational plan has all its requirements.

## Reactor and persistence decisions

`MeshTwinElement` owns one bounded admission channel and serializes each
namespace's fold. Its parent owns the supervisor and worker. Admission never
creates an unbounded waiter queue; once accepted, append and view publication
settle before cancellation returns. `drain` closes admission and joins that
owner. Persistence remains separately owned and must outlive the twin element.

`twin.merge` captures at most 64 canonical observations, preflights grants and
capacity, and writes an immutable `twin-batches` object of at most 64 KiB before
changing the derived view. The batch binds namespace, authenticated immediate
author, request identity, task CID, observations, and the predicted admission
result. Retrying returns the original stored addition count and savepoint,
including after other writes or reopening. A caller therefore receives an
operation result, not a falsely current snapshot. The persistence receipt's
durability and event fields remain intact for N=3 custody acknowledgements.

Startup follows bounded `object.list` pages with an explicit authorized local
reader. Every batch rechecks its original author against current tenant and
source grants; revoked authority fails reopening explicitly. Empty pages with
advancing cursors remain valid. Generic mesh writes reserve `twin-batches` for
this owner. Replica retention provides immutable custody only: without an
independently verifiable original-author proof, a replica does not silently
promote retained batches into its folded twin view. Authenticated residual
transfers can instead merge under the immediate issuer's explicit source grant.

`twin.get` returns the folded canonical observation. `twin.residual` returns
bounded missing observation bytes. `twin.references` examines at most 64 folded
owners and returns at most 64 relationship diagnostics per page; an owner and
diagnostic-offset cursor preserves every edge even if one observation has many
unresolved references. Continuation requires the first page's savepoint and
refuses a changed view. Tenant grants govern reads; source/key scopes constrain
observation admission. These are bounded local work units, not claims about
physical cache placement or cross-host transaction atomicity.

## Typed world projection

`twin.project {model, hardware, continent}` derives one bounded coordinate from
the current winners. `TwinWorld` interprets the family/model, quant, hardware
class/revision, rental, country and continent fields from the original Rust
world. Each candidate group has at most 64 observations; the response is at
most 64 KiB. Unknown fields remain extensible, but malformed required fields
produce diagnostics rather than an operational offer. Field interpretation
uses exact nonnegative integers and checked arithmetic. This is whole-record
winner selection, as in the source branches; unrelated observations do not
merge their individual fields into an invented record.

A hardware class uses the minimum `free_bytes` among its first measured
revision observations. Without a valid measured revision, its planning capacity
is 15/16 of the label in GiB. The result marks that distinction explicitly. The
model family's memory dimensions feed the source unified-KV formula: attention
and MTP KV share the context cells, recurrent state uses three rows per slot
(draft depth two), the configured runtime allowance is added, and one percent
of the budget is reserved with upward rounding. Context is the largest fitting
multiple of 256 up to the trained bound. Four slots require at least 131,072
context tokens; otherwise the fit uses one. MTP quants are considered by
descending artifact bytes, then name. Conflicting descriptors for one artifact
SHA256 are excluded and reported.

Country and continent references select eligible rental observations; exact
price and key order choose the cheapest. Posted price uses the existing
`MeshPrice` rational arithmetic and the rental branch's 1.33 factor with one
half-up rounding. This explicitly resolves a source difference: the older
digital-world line code rounded upward, while the rental settlement twin
rounded half-up. The response names its rounding rule. No billing settlement
is performed by this query.

Projection includes every consulted observation's CID and source strength,
the LineCas savepoint, selected artifact source when complete, fit, request and
disk sizing, and any malformed or missing inputs. It never writes an observation
and always marks `execution_qualified` false: a formula and a measured capacity
do not prove runtime compatibility, live availability, or permission to deploy.

## Deliberate operational boundaries

The successor does not synthesize a missing twin, implicitly import an old
bundle, or install a GPU probe. `twin.get` reports absence; explicit legacy
conversion and authenticated `twin.merge` provide the import and observation
paths. Automatic bundle-cache resolution, first-start measurement collection,
image acquisition, runtime profile installation, and the old vendor billing
lifecycle remain with their original application owners. The typed projection
ports the relevant sizing rules, not the Rust process's compiled artifact
registry or model-engine qualification. Generic references report malformed
or missing direct edges; they do not claim general graph-cycle analysis. These
boundaries avoid turning inferred planning data into authoritative measurements.
