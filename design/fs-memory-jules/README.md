# Filesystem-Based Memory: Jules Fan-Out Design

Maps arXiv:2607.26637v1 ("Filesystem-Based Memory for LLM Agents") onto
TrikeShed's BTRFS userspace VFS, Couch blob + CAS store, ISAM routes, and
ModelMux tooling palette.

## The Paper in One Line

One store class (rooted path hierarchy of markdown files), three roles
(management / search / execution), interchangeable tool harness. Declarative
memory and skills unify into one store.

## Key Findings Applied

- RQ1: where hierarchy lives (folders vs headings vs files) is model behavior.
  ConfixIsamIsomorphism indexes structure (headings as levels), not just files.
- RQ2: organization halves retrieval cost on large material. ISAM routes are
  the indexed traversal that delivers this.
- RQ3: search strength pays directly; management buys style. ModelMux.route
  selects different models per role.
- RQ4: stores get more useful as they grow. BTRFS reflinks make
  reorganization storage-free; ConfixWal gives durability.
- RQ5: the harness is a lever. ACP tool sets (write vs read vs BM25 vs shell)
  are the variable; ModelMux routes them.

## Layer Stack

The six concentric layers ("lillypads"). Each is a network processing
center that branches off the CAS blobs at a different granularity:

```
Ring 4 (pointcut)   PointcutMutableSeries + FieldSynapse + PolyglotBlackboardTaxonomy
                     intercepts every mutation with Evidence; GraalVM child VMs
Ring 3 (causal)     ReteNetwork + CausalGraphNodeIndex + BlackboardDagCausalGraph
                     production rules over the store trajectory M_1..M_T
Ring 2 (logical)    ConfixBlackboard + BlackboardOverlay + CellOverlay
                     epistemic metadata (role, provenance, evidence, dependencies)
Ring 1 (per-line)   LineCas + LineCasIndex + FunnelHashIndex
                     each line content-addressed with neighbor stamps [Prong 1+2]
Ring 0 (physical)   BtrfsReflinkStore / VolumeCasStore (block-level CAS) [Prong 1]
Ring -1 (mesh)      NUID concentric routing + Kademlia DHT + CasReplicationHook
                     ContentId is the universal address; subnets are lillypads
                     core < process < local < lan < mesh.worker < global.relay
```

The ContentId is the SAME address space across all rings. A whole-file CID,
a per-line CID, and a block CID are all sha256:hex. The DHT routes by NUID
subnet, but content is addressed by CID at any ring. A memory write:

  1. Ring 0: CasStore.put(bytes) -> ContentId; CasReplicationHook.onPut
     fires -> CID advertised to mesh DHT
  2. Ring 1: LineCas.spineInto -> each line CID also CAS-put + replicated
  3. spineCid = CasManifest identity -> publishable via IpfsBridge.publishIpns
  4. Ring -1: NUID with Capability.Cas("memory") routes the write to the
     correct subnet via DHT lookup

The paper's three roles operate at specific ring depths:
- Management: writes Ring 1, observed Ring 3, intercepted Ring 4
- Search: reads Ring 1, ranked Ring 2, proximity-scored Ring 3
- Execution: distills Ring 1, gated Ring 2, causal-linked Ring 3

## Five Prongs

| Prong | Layer | Files Touched | Terminal Surface |
|-------|-------|---------------|------------------|
| 1 | Physical | BtrfsReflinkStore, TinyBtrfsContract, MemoryFile (new) | Blackboard Cursor: subvolume ops |
| 2 | Index | ConfixIsamIsomorphism, ConfixIsamFactory, MemoryIndexRoute (new) | Blackboard Cursor: route queries |
| 3 | Protocol | AcpProtocol, ModelMux, MemoryTools (new) | Receipt provenance: harness profile |
| 4 | Skills | TrajectoryReduction, SkillFile (new), distillAttempt (new) | Blackboard cards: skill provenance |
| 5 | External | McpServerElement (new), LitebikeListenerElement pattern | MCP route: citation-backed search |

## Fan-In

All five prongs touch disjoint file sets (physical vs index vs protocol vs
distillation vs external API). Patches commute through PijulChannel. Fan-in
converges on the MCP server (Prong 5), which is the unified query interface
over the complete memory system.

## Build Gate

```
./gradlew jvmMainClasses --console=plain
```

No unit tests in the gate. Each prong's commonTest is for local verification.

## Dispatch Order

Prongs 1 and 2 are leaf layers with no cross-dependency; dispatch in parallel.
Prong 3 depends on the MemoryFile type from Prong 1 (tool dispatch targets).
Prong 4 depends on Prong 3 (management agent interface) and Prong 2
(retrieval guard). Prong 5 depends on Prong 2 (ISAM search), Prong 3 (read
tools), and Prong 1 (CAS resolution). It is the convergence point.

Recommended: dispatch Prongs 1+2 in parallel first. When both land, dispatch
Prong 3. When 3 lands, dispatch Prongs 4 and 5 in parallel.

## Reference

Zhou et al., "Filesystem-Based Memory for LLM Agents: Organization, Evolution,
and Sustainability," arXiv:2607.26637v1, 29 Jul 2026.
