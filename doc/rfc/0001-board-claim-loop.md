# RFC 0001 — The Board Claims Its Own Work

Status: Implemented 2026-09-04 (branch wf/kanban-claim-review + judge). Category: Standards Track for the Oroboros board.
Key words MUST, MUST NOT, SHOULD, MAY are as in RFC 2119.

## 1. Abstract

A card in READY is claimed by the board itself, worked by the daemon's own brain against a
brief grounded in the fact plane, judged by the production system against the card's own
RFC 2119 criteria, and closed, parked for a person, or sent back with a strike. No person is
in the loop unless the card asks for one. Hermes' kanban is the fore-runner (specify, judge,
failure breaker); this board is a causality and production system, so the judge is the plane.

## 2. State machine

    TODO --deps done--> READY --claim--> RUNNING --VERDICT MET + evidence--> REVIEW --judge:plane--> DONE
                          ^                |------ NEEDS-HUMAN | REVIEW: human | tag ------> REVIEW (a person decides)
                          |                '------ NOT-MET | no verdict | brain failed -----> READY (strike n)
                          '------------------------------------------------------ strike 3 ---> BLOCKED (owner "")

- A card in RUNNING owned by `claim:*` MUST NOT reach DONE except from REVIEW.
- REVIEW -> DONE MUST be signed by an actor other than the claimant (`judge:plane` or a person).
- A claim SHOULD land within one clock tick of READY when RUNNING is under its WIP limit (3).

## 3. The card's spec (RFC lines, one per line)

    GOAL: <one sentence>
    MUST: <verifiable condition>          # 1..n; the judge reads these
    SHOULD: <condition>                    # advisory
    MAY: <condition>
    OUT-OF-SCOPE: <thing not to touch>
    REVIEW: human                          # a person decides, no matter the reply
    MODEL: <id>                            # per-card model, else the newest Hermes card
    TOKENS: <n>                            # per-card budget, else 2048

A spec without a MUST gets `MUST-1: name the concrete next action, citing one evidence id from
the plane, or state that none applies`.

## 4. The brief the brain receives (PlaneBrief)

    Card <id> — brief (RFC 2119: MUST, SHOULD, MAY)
    GOAL: …
    ACCEPTANCE:            MUST-1: … / SHOULD-1: …
    OUT-OF-SCOPE:          …
    EVIDENCE on the daemon's plane (cite these ids verbatim):  ≤ 16 `partition/id (kind)` lines
    DAEMON: heap a/b MB · G1New n gcs · jit m compiles
    LESSONS (from this board's own receipts):  AVOID model: k of n claims failed — last: …  /  DO model: …
    FLOW: (the state machine above)
    REPLY (exactly this shape; the plane judge parses it, a person does not):
      VERDICT: MET | NOT-MET | NEEDS-HUMAN
      MUST-k: MET | NOT-MET — evidence: <one id from EVIDENCE, or none>
      ACTION: <= 3 sentences

Evidence rows are the plane facts whose id or text mentions the card's terms, source before
build output. Lessons are folded from `kanban/claim/<jobId>` receipts, per model.

## 5. The judge (PlaneJudge)

    DONE   iff VERDICT MET and every MUST-k is MET with an evidence id that IS a fact on the plane
    REVIEW iff the card asks for a person (REVIEW: human, tag human-review/experiment) or VERDICT NEEDS-HUMAN
    RETRY  otherwise (NOT-MET, no VERDICT, a MUST without plane evidence, brain call failed)

RETRY writes `kanban/rule/reaper/judge-<jobId>-r<rev>` (a strike the reaper counts) and moves
the card to READY; the third strike moves it to BLOCKED with the owner cleared.

## 6. Receipts

`kanban/claim/<jobId>` = {owner, model, ok, content|error, atMs, revision, facts, decision, why,
verdict, criteria:[{label, met, evidence}]}. `kanban/committed/<jobId>/<seq>` is every move.

## 7. Quota

The brain is ModelMux over the QuotaLegion walked back from Hermes' success ledger; a claim
costs one call within the provider's proven daily budget. No Claude model is in the loop.

## 8. The coding-agent lane (2026-09-06, Forge genesis Cut A)

A card may say `AGENT: codex` (or `opencode`, or `claude` when the operator enabled it) and,
optionally, `AGENT-BUDGET: <seconds>` (clamped to 840, under the reaper's fifteen minutes).
Such a card is claimed by owner `claim:agent:<id>` instead of `claim:brain`; every guard that
keys on the `claim:` prefix applies unchanged. The worker makes a shared clone of the daemon's
repository at its committed HEAD under `<forgeHome>/agents/<runId>/repo`, runs the CLI there
non-interactively with a whitelisted environment and the brief on its input, bounded by the
budget, then records `git diff --cached --binary` against the base and the CLI's transcript as
CAS citizens and attachment documents. The brief gains an `AGENT:` block naming the reader, the
clone and the evidence id `blackboard/agent/run/<runId>`; that id is on the plane only when the
diff is non-empty, so a MUST "met" by a file change needs a real change. The CLI's last message
is the reply the judge reads, in the same REPLY shape. A budget kill parks the card in REVIEW
(a person decides) and is not a strike. `kanban/claim/<jobId>` gains `agent, runId, patchCid,
transcriptCid, exit, killed, truncated, patchBytes`; `agent/run/<runId>` is the run's own
receipt; `GET /api/agents` is the roster and `GET /api/agents/runs` the receipts.

Opt-in per card and outside section 7's quota: no agent runs unless a card names one, and no
card names one unless a person wrote the line. An agent not installed or not enabled here means
the claim is not taken and the card stays READY, exactly as a missing brain does. Fan-out stays
chat-only: `AGENT:` lines never reach child cards. `claude` is installed on the owner's Mac but
is not in the default enabled set; `--agents codex,opencode,claude` (or `TRIKESHED_AGENTS`) says
otherwise.
