# Flywheel Poison Threshold

**Canonical poison threshold: NOON CST on the current flywheel-day.**

Branches created **AFTER NOON CST today** are **VIABLE**.
Branches created **BEFORE NOON CST today** (or older days) are **POISON**.

## Decision rule

For every branch in `git branch -r`:
- if `created_at >= today 17:00 UTC` AND not yet merged → merge or process as viable
- otherwise → close as stale, delete from origin (do not force-write history)

## CST = UTC-5 (CDT in July)

`NOON_CST_THRESHOLD_UTC = 17:00:00` (the current flywheel-day).

## Why

Stale branches accumulate (e.g. `overnight`/`flywheel-unification` PRs that never
made it through settlement). The flywheel must not dispatch to them or attempt
to harvest them — they are stale deliverables. Viable branches are recent activity
that came in during the current session and are still actionable.

## Current status

This page records a historical operator policy, not an implemented reducer.
The current Oroboros funnel does not infer artifact validity from branch age:
it observes Jules API bytes into CAS/WAL and requires causal review plus an
exact build/integration gate. There is no parallel direct-HTTP Jules CLI.

Do not automate branch deletion from this document. A future typed retention
reducer must preserve immutable settlement tags and emit an explicit receipt.

## Last applied

`2026-07-23 13:42 CDT` (18:42 UTC). 5 post-noon branches merged (PRs #304–#308). 3 pre-noon branches closed as poison (#300, #301, #302). Zero open PRs.
