{
  "ConfixDoc": {
    "cursor": {
      "J01": "pending",
      "J02": "pending",
      "J03": "pending",
      "J04": "pending",
      "J05": "pending",
      "J12": "pending",
      "jules_code_captured": [
        "JULES_INTEGRATION.md",
        "JULES_TASK_TREES.md",
        "PACKAGE_JOBS.md"
      ],
      "reanimations_logged": true,
      "nvidia_key_present": false
    },
    "reified": true,
    "format": "confix-json",
    "jules_docs": {
      "JULES_INTEGRATION.md": "# Jules Integration Configuration for TrikeShed\n# This file documents the Jules integration setup for this repository\n\n# =============================================================================\n# REPOSITORY CONFIGURATION\n# =============================================================================\nREPO_OWNER=jnorthrup\nREPO_NAME=TrikeShed\nREPO_ROOT=/Users/jim/work/TrikeShed\n\n# =============================================================================\n# JULES API CONFIGURATION\n# =============================================================================\n# Jules API key from Google Cloud (OAuth 2.0, not API key)\n# Get from: jules CLI auth or Google Cloud Console\n# JULES_API_KEY=AQ...\n\n# =============================================================================\n# GITHUB CONFIGURATION\n# =============================================================================\n# GitHub token for PR operations\n# GITHUB_TOKEN=ghp_...\n\n# =======================================================",
      "JULES_TASK_TREES.md": "# TrikeShed J01-J12 Task Tree Breakouts\n\nGenerated from PACKAGE_JOBS.md \u2014 one task tree per job for Jules dispatch.\nEach job maps to a Jules session. Use these as the authoritative prompt checklist.\n\n---\n\n## J01 \u2014 Kernel Algebra (Distance 0, Switch: none)\n**Packages:** `borg.trikeshed.lib`, `borg.trikeshed.charstr`, `borg.trikeshed.num`\n**Existing interfaces:** `Join`, `Twin`, `Series`, `j`, `\u03b1`, `MutableSeries`, `CharStr`, packed twins\n\n### Task Tree\n```\nJ01-KERNEL-ALGEBRA\n\u251c\u2500\u2500 J01-01: Canonical constructor/import path for j, joins, Series, metadata products\n\u2502   \u251c\u2500\u2500 Write test: j() factory, Join.companion, Series companion methods\n\u2502   \u251c\u2500\u2500 Implement: single canonical import path in lib/\n\u2502   \u2514\u2500\u2500 Verify: all upper packages consume directly (no adapters)\n\u251c\u2500\u2500 J01-02: Remove package-level semantic duplication (tests prove equivalent behavior)\n\u2502   \u251c\u2500\u2500 Audit: lib/, charstr/, num/ for duplicate semantics\n\u2502   \u251c\u2500\u2500 Write failing tests for each duplicate\n\u2502   \u251c\u2500\u2500 Converge implementations\n\u2502   \u2514\u2500\u2500 Del",
      "PACKAGE_JOBS.md": "# TrikeShed Package Job Program\n\n## Summary\n\nTrikeShed is **one root Kotlin Multiplatform Gradle project**. `./gradlew projects` reports no subprojects. The source tree currently contains **130 declared Kotlin packages**; those are grouped below into **12 independent remedial jobs**, not 130 modules.\n\nThe dependency direction is fixed:\n\n```text\nDistance 3   Forge / Kanban / CCEK / graph surfaces\n                 \u2191 typed Confix events and cursor projections\nDistance 2   integration, compute, transports, external ingress\n                 \u2191 schema-bearing ingestion streams\nDistance 1   structured ingestion, reduction, ISAM/persistence\n                 \u2191 metadata-preserving Cursor / Series values\nDistance 0   TrikeShed algebra, collections, cursor, platform substrate\n```\n\nMaximum architectural distance from the TrikeShed kernel is 3. Pure aliases, generated bindings, logging shims, and tiny compatibility utilities do not receive standalone tasks; they remain owned by their nearest package "
    },
    "analysis": "sustained"
  }
}