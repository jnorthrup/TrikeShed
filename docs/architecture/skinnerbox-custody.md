# Skinnerbox cell and custody decisions

The successor's N=3 cell follows the distinct identity/failure-domain custody
requirements in Skinnerbox `main` (`bd82134f6fcae377ec1d2ec569bdec855245bce1`),
with digital twin inputs from `twins-prod-20261022`
(`c0bc7602470326fb14b6b0076c7f05dc55d6573f`) and exact hosting inputs from
`rental-twin-pickyourai` (`e96ab339c7a9a5ba2f74e32369f4bb4228dea2ed`).

`MeshCell` captures exactly three immutable enrolled peers and the canonical
placement decision. The first member is the executor; the next two are replica
custodians. Identity, endpoint, scope, failure-domain labels, namespace, ordered
membership and decision bytes all bind the epoch CID. Duplicate identities,
duplicate failure-domain labels and members without namespace scope fail before
admission. Labels are explicit operator configuration; they do not prove actual
physical independence. Membership changes require a new explicit epoch.

Decision input is bounded before copying, then validated as one canonical CBOR
map of at most 64 KiB, depth 16 and 16,384 items. This allows the complete bounded
placement matrix without weakening the smaller operation-payload item limit.
Returned epoch, decision and proof bytes are defensive snapshots.

`MeshCustody` signs its peer, namespace, epoch, immutable record CID, observation
time and durable flag. Verification rejects extra or incorrectly typed fields,
noncanonical frames, altered signatures and stale or future proofs beyond 30
seconds. Nonnegative time subtraction avoids overflow at epoch zero and
`Long.MAX_VALUE`. A bounded proof set rejects duplicate identities, so repeating
one peer's signature cannot satisfy N=3. Each signed acknowledgement remains a
peer's statement that it durably retained these bytes, not evidence of task
execution, leader election, consensus, or an automatically adopted Twin fold.
