---
name: rga
description: "Recursive gap analysis. Audit a claimed artifact against what backs the claim, then audit each backing against its backing until you hit hard evidence. Emits concrete gaps plus one best debt-reduction cut. Use when the user says gap analyze, audit, iron out the vision, what's fake vs real, /rga, or asks whether documented behavior matches observable behavior."
version: 2.0.0
platforms: [linux, macos]
---

# Borrowed from Hermes

Canonical skill tree: `/Users/jim/.hermes/skills/research/rga`

Follow the full procedure in **`/Users/jim/.hermes/skills/research/rga/SKILL.md`**. Every `references/...` path named there lives under that directory (e.g. `/Users/jim/.hermes/skills/research/rga/references/trikeshed-rga-patterns.md`).

To wire this project to the live Hermes tree (symlink, no duplicate corpus):

```bash
./scripts/link-rga-skill-from-hermes.sh
```

That replaces `.cursor/skills/rga` with a symlink to `~/.hermes/skills/research/rga` so relative references and templates resolve locally.
