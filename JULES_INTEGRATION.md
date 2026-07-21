# Jules Integration Configuration for TrikeShed
# This file documents the Jules integration setup for this repository

# =============================================================================
# REPOSITORY CONFIGURATION
# =============================================================================
REPO_OWNER=jnorthrup
REPO_NAME=TrikeShed
REPO_ROOT=/Users/jim/work/TrikeShed

# =============================================================================
# JULES API CONFIGURATION
# =============================================================================
# Jules API key from Google Cloud (OAuth 2.0, not API key)
# Get from: jules CLI auth or Google Cloud Console
# JULES_API_KEY=AQ...

# =============================================================================
# GITHUB CONFIGURATION
# =============================================================================
# GitHub token for PR operations
# GITHUB_TOKEN=ghp_...

# =============================================================================
# ZENITH INTEGRATION
# =============================================================================
# Zenith project ID for telegraphing Jules forks
# ZENITH_PROJECT_ID=...

# =============================================================================
# JULES JOB MAPPING (J01-J12 from PACKAGE_JOBS.md)
# =============================================================================
# Each job maps to a Jules session. Update as sessions complete.

# J01 - Kernel algebra
JULES_J01_SESSION=14840399884225250297
JULES_J01_URL=https://jules.google.com/session/14840399884225250297
JULES_J01_STATUS=Completed

# J02 - Collections/mutation
JULES_J02_SESSION=10343031295565610478
JULES_J02_URL=https://jules.google.com/session/10343031295565610478
JULES_J02_STATUS=Planning (new session)

# J03 - Cursor/Confix
JULES_J03_SESSION=16753998972266025303
JULES_J03_URL=https://jules.google.com/session/16753998972266025303
JULES_J03_STATUS=Planning (new session)

# J04 - Platform/NIO
JULES_J04_SESSION=7135063974888942038
JULES_J04_URL=https://jules.google.com/session/7135063974888942038
JULES_J04_STATUS=Planning (new session)

# J05 - Structured ingestion
JULES_J05_SESSION=12784702627924403935
JULES_J05_URL=https://jules.google.com/session/12784702627924403935
JULES_J05_STATUS=Planning (new session)

# J06 - ISAM/persistence
JULES_J06_SESSION=6812140497092679826
JULES_J06_URL=https://jules.google.com/session/6812140497092679826
JULES_J06_STATUS=Planning (new session)

# J07 - LCNC reduction
JULES_J07_SESSION=11278868115855868767
JULES_J07_URL=https://jules.google.com/session/11278868115855868767
JULES_J07_STATUS=Planning (new session)

# J08 - Transport/protocol
JULES_J08_SESSION=4044040330023918642
JULES_J08_URL=https://jules.google.com/session/4044040330023918642
JULES_J08_STATUS=Planning (new session)

# J09 - DHT/routing
JULES_J09_SESSION=4256545542739399138
JULES_J09_URL=https://jules.google.com/session/4256545542739399138
JULES_J09_STATUS=Planning (new session)

# J10 - External API ingress
JULES_J10_SESSION=5512307911648352632
JULES_J10_URL=https://jules.google.com/session/5512307911648352632
JULES_J10_STATUS=Planning (new session)

# J11 - Compute/Graal
JULES_J11_SESSION=8536800141685782909
JULES_J11_URL=https://jules.google.com/session/8536800141685782909
JULES_J11_STATUS=Planning (new session)

# J12 - Forge top
JULES_J12_SESSION=13413214599923051064
JULES_J12_URL=https://jules.google.com/session/13413214599923051064
JULES_J12_STATUS=In Progress (existing)

# =============================================================================
# BRANCH PATTERNS
# =============================================================================
# Jules branches follow pattern: jules-<job-name>-<session-id>
# or: jules/<job-name>-<session-id>
# 
# Examples:
#   origin/jules-mutation-test-consolidation-13311535945178380322
#   origin/jules-htx-aria2-3073524619372447372
#   origin/jules/userspace-ebpf-engine-16367108322000935405

# =============================================================================
# MONITORING COMMANDS
# =============================================================================
# One-shot check:
#   cd /Users/jim/work/TrikeShed && jules-pr-monitor watch --once

# Continuous watch (background):
#   nohup jules-pr-monitor watch --interval=300 > jules-monitor.log 2>&1 &

# List active Jules branches:
#   jules-pr-monitor list

# Review specific PR:
#   jules-pr-monitor review 42 VAL-FP-001

# Telegraph Zenith at architectural fork:
#   jules-pr-monitor telegraph jules-ebpf new_userspace_element

# =============================================================================
# CRON INSTALLATION
# =============================================================================
# To install the cron job:
#   crontab /Users/jim/work/TrikeShed/.zenith/cron/jules-monitor.cron
# 
# Or using the skill:
#   jules-pr-monitor install --workspace=/Users/jim/work/TrikeShed

# =============================================================================
# JULES CLI USAGE
# =============================================================================
# Planning rule: use Gemini's large context for a broad read and a narrow write.
# Before implementation, trace each owned symbol through definitions,
# implementations, direct callers, tests, manifests, and recent git history.
# Scan peripheral interfaces at least two hops out and produce a temporary matrix:
#   symbol | definition | implementors | callers | overlap | canonical owner
# Classify overlap as duplicate, shared responsibility, adapter boundary, or
# intentionally distinct semantics. Reuse/consolidate the canonical owner rather
# than adding another interface, adapter, typealias, helper, module, or dependency.
# Keep the final edit scope small. Put the scan matrix in the session/PR description,
# never in generated repository planning documents.
#
# List all sessions:
#   jules remote list --session
#
# Pull session result:
#   jules remote pull --session <SESSION_ID>
#
# Pull and apply patch:
#   jules remote pull --session <SESSION_ID> --apply
#
# Create new session:
#   jules new --repo jnorthrup/TrikeShed "Task description"
#
# Create parallel sessions:
#   jules new --repo jnorthrup/TrikeShed --parallel 3 "Task description"

# =============================================================================
# ZENITH TELEGRAPHING
# =============================================================================
# When a Jules PR introduces architectural changes, telegraph to Zenith:
#
# Fork types that trigger telegraph:
#   - new_package
#   - new_opk_family
#   - new_lcnc_mode
#   - new_userspace_element
#   - wire_codec_change
#   - signal_facet_change
#
# Zenith mission naming: trikeshed-jules-<branch-name>
# Contracts to evaluate based on fork type (see jules-pr-monitor skill)

# =============================================================================
# ENVIRONMENT SETUP (for cron/background jobs)
# =============================================================================
# Add to ~/.zshrc or ~/.bashrc:
# export TRIKESHED_ROOT=/Users/jim/work/TrikeShed
# export JULES_API_KEY=your_oauth_token
# export GITHUB_TOKEN=your_github_token
# export ZENITH_PROJECT_ID=your_zenith_project_id

# =============================================================================
# NOTES
# =============================================================================
# - Jules is the SLOW agent (eventual delivery, hours per task)
# - Do NOT poll Jules synchronously; use bijective sync pattern
# - Jules sessions map to J01-J12 package jobs
# - Each session should produce a PR-ready branch
# - Zenith conductor coalesces Jules output at mission closure (4hr window)
# - Paths leaked to Jules MUST be relative to project root, not above it
# - ~/.zenith (harness home) is DISQUALIFIED from Jules context