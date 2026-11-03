# Work selection

`work.select` compares explicitly supplied offers for one unit of work. Each
offer has `id`, `kind` (`model` or `service`), `feature`, `currency`,
`totalMicros`, `expiresAt` (Unix milliseconds), `source`, and `available`
(boolean). `totalMicros` is the quoted total for the work being planned, in
millionths of the named currency. It is not a unit rate. Inputs also specify
the required `feature`, `currency`, and nonnegative `ceilingMicros`. Both money
fields are canonical decimal digit strings (for example `"0"` or `"1250"`),
without signs, leading zeroes, decimal points, or JSON numbers. A model offer
also requires the exact roster `endpoint` and wire `model` it describes.

The node chooses the lowest declared total among matching, available-declared,
unexpired quotes at or below the ceiling. Equal totals use input order. It
rejects duplicate ids and reports each excluded offer's reason. Unknown
prices are excluded, not treated as free; currencies are not converted. Up to
256 offers are accepted, with monetary strings bounded by 2^53−1. The expiry
check applies to parsed numeric values; JSON parsing may round the original
expiry spelling before this check.

`model` and `service` outputs are boolean guards for separate `scope` rings;
both are false when no offer qualifies. `selection` contains the selected
declaration, while `decisions`, `criterion`, `asOf`, and `status` expose how
the choice was made. The status is `planning_only_unverified_quotes`: the
node does not verify availability or prices, reserve capacity, authorize
spending, or execute work. Wire `selection` to the required `work.model`
`selection` input to execute only that endpoint/model pair. An unfed selection
skips the node, so it cannot fall back to another route. The seat must
explicitly allow it, the active roster must contain it, and the ModelMux
dialog pins its provider identity, wire model and destination; a dialog that
cannot bind an exact route refuses the request. A credential-free model must
be explicitly registered as such with an exact destination; it never resolves
a KeyMux secret. This binding does not revalidate quote expiry or authenticity,
or enforce the quoted cost. A selected service requires a separately
registered service executor and its own authorization,
current quote, and quote authenticity checks. Human review continues through
the existing authenticated review workflow.

ModelMux does not reserve provider quota or enforce account-wide spending.
A reactor lease belongs to the owner that acquired it, and a model call does
not release another owner's lease.

Model file format and runtime are separate facts. For example, GGUF is a
format; MLX is a runtime/acceleration stack. Neither implies a price or an
available executor.

When a ModelMux call runs with the same live `MuxReactorElement`, its non-cached
chat, stream and embedding transports admit at most 72 calls, run at most eight
transports at a time, and serialize calls using the same resolved binding path.
The bounded input channel holds at most 64 calls; admitted calls may also wait
for a binding or transport slot. A full or
closed queue refuses new work. After waiting, the worker resolves the binding
again, checks the mux's current quota observation, and sends only if the
credential and destination are unchanged. Provider quota observations are
recorded before the worker releases that binding. The cancellation case
requires `HtxReactorElement` or a custom transport that implements its
`HtxResponseObserver` contract; an arbitrary route service or HtxElement
override may discard a late response. Closing the reactor stops admission and
drains accepted work before its transport is closed.

The identity is a logical binding path (or a keyless endpoint), not proof of
physical credential or account identity. Two paths may refer to the same
credential or provider account, and separate reactors or calls outside the
reactor are outside this limit. Sharing the reactor alone does not share quota
observations: ModelMux instances must also share the same `QuotaLegion` to share
a 429 cooldown. Oroboros supplies one legion across its catalog rebuilds.
Calls waiting for one binding do not occupy transport slots, but the queue does
not guarantee fairness. Cancellation after
admission waits for a started transport to finish; a canceled caller's queued
work is skipped before it starts. No fan-out probing or quota spending is
performed by this admission mechanism.

Chat accounts for time spent before and during its queue wait when setting the
transport timeout. Its existing breadcrumb transaction joins accepted storage
work on cancellation, and a transport that cannot stop its submitted I/O can
also delay return; this is not a hard wall-clock deadline. Stream and embedding
calls continue to use the transport's default timeout.

When a source replaces a credential in place without changing its binding path,
the existing path-level 429 cooldown can also block the replacement until reset.
The mux does not infer a versioned credential identity from the secret.
Stream and embedding HTTP quota headers are observed, but their provider token
usage is not currently charged to the legion's spent-token telemetry.
