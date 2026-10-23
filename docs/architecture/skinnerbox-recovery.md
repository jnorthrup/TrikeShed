# Retained snapshot recovery

Recovery restores an explicit snapshot to the original namespace owner under
the original pinned cell. It does not elect a replacement owner, infer a missing
tail, or establish that the selected custodian contains the latest distributed
state. A snapshot can be older than later acknowledged mutations; operators must
select the retained source and cutoff deliberately before resuming traffic.

## Decisions

| Decision | Consequence |
| --- | --- |
| Recovery is an offline operator operation | It opens the existing common persistence owners and their exclusive writer leases. No recovery verb is available through the HTTP API or the mesh public operation allowlist. Stop affected serving processes before accessing their state directories. |
| Bind the manifest to immutable placement | The canonical CBOR manifest records the exact cell bytes, epoch, namespace, ordered packet CIDs and original cutoff sequence. Restore requires the configured owner identity and identical cell. A changed member, endpoint or placement decision requires a separate explicit handoff design. |
| Trust the selected local snapshot source | Source files must come from a configured durable custodian and a trusted operator-controlled copy. Content hashes establish byte integrity. Custody signatures are coordinator retention evidence; recovery does not claim an original author signature over each event or authenticate an arbitrary downloaded snapshot. |
| Validate the full selected history before writing | Every retention envelope must bind its tenant, epoch, actor and mutation digest. Every event must have a distinct request id and increasing original sequence, valid content identity, and its exact prior row revision in the selected set. Missing predecessors, altered packets, mismatched epochs and insufficient destination budgets fail before recovery begins. |
| Preserve receipts and dedupe identities | Import retains original event bytes, revision, author, request digest, sequence and value. It does not replay each mutation as a new request. Retrying an old creation after a recovered deletion returns its old receipt without resurrecting the row. Twin batches remain ordinary immutable object events and replay through the existing Twin owner. |
| Preserve sequence gaps explicitly | Source metadata and commits for other namespaces are not part of the selected event history. Recovery inserts labelled gap records to preserve original event sequences; these are not reconstructed lost commits. Consumers must restart the change feed after restore rather than reuse a cursor from the lost database. |
| Bound coordinator work and memory | At most 4096 events, 512 KiB manifest bytes, 65536 original sequence positions, and one 96 KiB packet at a time. Source inventory pages scan at most 64 records. Gap advancement publishes at most eight records per persistence request, then yields. Preflight estimates gap-marker bytes as well as events and values against destination limits. |
| Keep incomplete destinations closed | A durable start marker binds the manifest CID, expected event count and cutoff. Normal persistence requests remain blocked across restarts until a matching end marker confirms all declared events. Missing source data or cancellation after start leaves an explicit incomplete destination. The CLI retains that directory for inspection and refuses to overwrite it on retry. |
| Re-establish custody after import | Recovered events start pending custody. Before admitting a new mutation, the original owner repairs predecessor custody with the other pinned members. Local snapshot recovery alone is not an N=3 acknowledgment. |

## Commands

The common command implementation is exposed by the JVM `SkinnerboxCli` entry
point. The files below are paths supplied to that command, not credentials in
the manifest:

```text
snapshot CONFIG_JSON SEED_FILE SOURCE_STATE NAMESPACE MANIFEST_CBOR
restore CONFIG_JSON OWNER_SEED_FILE SOURCE_STATE NAMESPACE MANIFEST_CBOR NEW_STATE
```

`snapshot` enumerates retained packets in the selected namespace, checks the
retained cell, sorts original event sequences, validates predecessor chains,
and creates a manifest without replacing an existing file. It stores packet
identities, not a second copy of all payloads. Keep the source CAS/state directory
available with the manifest.

`restore` first checks the manifest and every selected source packet. The new
state directory must be absent, separate from the source, and under an existing
parent directory. The owner seed must match the configured original owner for
the selected namespace. The command opens no listener. After it succeeds, start
the owner using `NEW_STATE`, the same configuration, and the same owner seed.

If restore stops after its durable start marker, normal admission stays closed.
Inspect and retain the incomplete directory as needed, then retry into another
fresh directory from the same trusted manifest and source. An interrupted
destination is never silently deleted, promoted, or served as a complete copy.

The manifest's `cutoffSequence` means only the greatest event sequence explicitly
included in that manifest. Neither an empty inventory nor an apparently complete
per-row chain proves that a later independent row or namespace mutation did not
exist elsewhere. This implementation has no distributed snapshot consensus or
automatic failover.

Validation uses real durable stores to restore binary values and deletions,
compare original retry receipts after reopening, reject a missing predecessor
before destination admission, enforce pinned ownership, and prove that an
interrupted copy cannot complete early or serve normal writes after reopening.
