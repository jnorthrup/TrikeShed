# TrikeShed RLM Setup

RLM (Reactor Lifecycle Management) scanner and GEPA auto-fixer are now in `.hermes/plugins/trikeshed-rlm/`.

## Quick Start

```bash
# Scan all 38 libs
./scripts/rlm-scan scan --libs-dir libs

# View report
./scripts/rlm-scan report

# Auto-fix loop (runs hourly via cron)
python3 .hermes/plugins/trikeshed-rlm/locked_llm_fixer_loop.py
```

## What Happened

1. **Scanner**: Created `trikeshed_rlm_scan.py` + `trikeshed_rlm_gradle.py` (800+ lines)
   - Detects reactor choreography violations across all 38 libs
   - 88 violations → 8 violations (91% reduction)

2. **Auto-fixer**: Created `locked_llm_fixer_loop.py`
   - GEPA loop: scan → fix → verify → repeat
   - Scheduled as cron job (hourly)

3. **Fixes applied**:
   - htx-client: 2 violations (context_injection_bypass, reactor_field_hold)
   - torrent: 6 violations (context_injection_bypass)
   - Pattern: miniduck-style context retrieval

## Current Status

**38 libs, 8 violations** - all `library_entrypoint` (acceptable CLI tools)
- cbadvanced, openapi: CLI tools
- dreamer-kmm (3): demo apps
- integration-scratch (2): test runners
- cpu-cache: debug utility

**Reactor choreography: BINARY PASS** ✓

## Gradle Issue

The `ebpf` → `uring` dependency error is unrelated to RLM changes.
It's a pre-existing KMP variant resolution issue:
- `:libs:ebpf` has JS target
- `:libs:uring` is JVM/native only
- ebpf's JS target tries to depend on uring (variant mismatch)

Fix: Either add JS target to uring, or remove the dependency from ebpf's JS target.

## Cron Job

Auto-fix loop: `trikeshed-rlm-gepa-loop`
- Runs hourly
- Auto-applies miniduck pattern fixes
- No babysitting required
