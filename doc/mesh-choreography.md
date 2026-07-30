# Enterprise Choreography Patterns for Cooperative Agent Concentric Meshes

This document details the choreography patterns mapped to work, IPC, RPC, and broadcast within a cooperative agent concentric mesh. It utilizes the TrikeShed CCEK (Capability, Concentric Subnet, Escalation, Key) algebra, bridging model-multiplexing (ModelMux) and DHT-enabled routing over SCTP-style associations.

## 1. Concentric Mesh Topology & Node Allocation

The mesh organizes nodes into strict concentric subnets. A node is only admitted if it possesses the exact cryptographic capacity and trait profile matching the destination space. Space allocation is exact: no more, no less.

```text
    [ Global Relay / Peer-to-Peer Backplane (DHT) ]
             |
             v
    +-----------------------------------------------+
    | Mesh Worker Ring (Level 4)                    |
    |   Exact Node Allocation: max 12 per ring      |
    |   [ Node A ] <---> [ Node B ]                 |
    |        |                                      |
    |   +---------------------------------------+   |
    |   | LAN / Localhost Ring (Level 3)        |   |
    |   |   [ Sub-node A1 ] <-> [ Sub-node A2 ] |   |
    |   |        |                              |   |
    |   |   +-------------------------------+   |   |
    |   |   | Process Ring (Level 2)        |   |   |
    |   |   |   [ Core Execution (Level 1)] |   |   |
    |   |   +-------------------------------+   |   |
    |   +---------------------------------------+   |
    +-----------------------------------------------+
```

**Security Insight (Exact Space Allocation):**
By enforcing that a concentric level admits *exactly* the needed number of nodes (based on capability metrics), the system inherently resists Sybil-style flooding and containment breaches. A node cannot join a higher-level sub-ring without explicit capability verification via the `NUID` trait space.

## 2. Reactor & CCEK Fan-In / Fan-Out

The core abstraction for dispatch is the `NuidFanoutElement` working in concert with the async `MuxReactorElement`.

*   **Fan-In (Ingress):** External signals (e.g., HTTP, SCTP) enter through a `LitebikeListenerElement` and are transformed into `NUID`-bound claims.
*   **Fan-Out (Egress/Dispatch):** Claims are broadcast to the narrowest valid concentric workgroup. If no local worker accepts the claim, it escalates concentrically outward.

```text
 [ Protocol Transport (SCTP / DHT-Route) ]
       |
       | (Ingress Bytes)
       v
 +---------------------------+
 | LitebikeListenerElement   | (Transforms bytes to Claim)
 +-------------+-------------+
               |
               | NUID Claim (Capability + Subnet)
               v
 +---------------------------+
 | NuidFanoutElement         |
 |   (Fan-Out Dispatcher)    |
 +----+--------+--------+----+
      |        |        |     (Concentric Escalation)
      v        v        v
   [L1]     [L2]     [L3]
   Core     Proc     LAN
```

## 3. Work-Stealing and Work-Queuing

The work distribution relies on a pull-based slot mechanism combined with hierarchical queuing.

```text
 [ Central Reactor Agenda / JobNexus ]
         |
         | (Job Snapshot / Dependency Graph)
         v
 [ Bounded ChannelQueue (L2) ] <---- [ Worker A (Idle) ] (Steals work)
         |
         v
 [ Worker B (Busy) ] --> [ Local Overflow Queue ]
                           |
                           v (Escalates if timeout)
                     [ Bounded ChannelQueue (L3) ]
```

**Security Insight (Queueing):**
Work-stealing bounds memory and prevents resource exhaustion (OOM). A node only requests work when it has compute capacity. If a node fails, the unacknowledged work is naturally re-queued by the `JobSupervisorElement`. The cost of adding/removing nodes is O(1) in terms of routing table updates via the DHT backplane.

## 4. Conversational Components & Causal Assessments

For ModelMux agents, tasks aren't just isolated RPCs; they form causal conversational chains (DAGs). Each step's output is an immutable fact (CID) that feeds the next node.

```text
 [ ModelMux Ingress ]
         |
         v (CID: 0x1A)
 +-----------------------+
 | Causal Graph Node 1   | ---> (Reporting Channel) -> [ Audit Log ]
 | (Information Intake)  |
 +-----------+-----------+
             |
             v (CID: 0x2B)
 +-----------------------+
 | Causal Graph Node 2   | ---> (Reporting Channel) -> [ Audit Log ]
 | (Transformation)      |
 +-----------------------+
```

*   **IPC/RPC:** Handled via Confix-serialized payloads routed directly between reactor endpoints based on NUID.
*   **Broadcast:** Handled by escalating a capability claim through the fanout element until a subscriber acknowledges or the scope limit is reached.
*   **Reporting:** Side-channels (reporting channels) emit telemetry and validation events to the `JobLog` without blocking the main conversational flow, guaranteeing auditability for enterprise compliance.
