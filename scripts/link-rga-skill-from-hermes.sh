#!/usr/bin/env bash
# Link Cursor project skill to the canonical Hermes copy (no duplicate references/).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HERMES="${HERMES_HOME:-$HOME/.hermes}/skills/research/rga"
if [[ ! -f "$HERMES/SKILL.md" ]]; then
  echo "Hermes rga skill not found at: $HERMES/SKILL.md" >&2
  exit 1
fi
mkdir -p "$ROOT/.cursor/skills"
if [[ -e "$ROOT/.cursor/skills/rga" && ! -L "$ROOT/.cursor/skills/rga" ]]; then
  rm -rf "$ROOT/.cursor/skills/rga"
fi
ln -sfn "$HERMES" "$ROOT/.cursor/skills/rga"
echo "Linked $ROOT/.cursor/skills/rga -> $HERMES"
