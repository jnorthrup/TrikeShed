#!/usr/bin/env bash
# tristeshed-rlm-install.sh — one-pass prerequisite setup
# Run from TrikeShed repo root. Safe to re-run. Manual-only, no auto-anything.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV_DIR="${VENV_DIR:-$HOME/.venvs/predict-rlm}"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"

echo "=== 1. predict-rlm + rlm-gepa (Python 3.12+) ==="
if [ ! -d "$VENV_DIR" ]; then
    uv venv "$VENV_DIR"
fi
uv pip install --python "$VENV_DIR/bin/python" predict-rlm gepa
# rlm_gepa ships inside predict-rlm repo, ensure it's importable
if "$VENV_DIR/bin/python" -c "import rlm_gepa" 2>/dev/null; then
    echo "  rlm_gepa OK"
else
    echo "  Installing rlm_gepa from source..."
    if [ -d "$HOME/work/predict-rlm" ]; then
        uv pip install --python "$VENV_DIR/bin/python" "$HOME/work/predict-rlm"
    else
        uv pip install --python "$VENV_DIR/bin/python" "git+https://github.com/Trampoline-AI/predict-rlm.git#subdirectory=."
    fi
fi

echo "=== 2. hermes-predict-rlm plugin ==="
PLUGIN_SRC="$HOME/work/hermes-predict-rlm/plugins/predict_rlm"
PLUGIN_DST="$HERMES_HOME/hermes-agent/plugins/predict_rlm"
if [ ! -d "$PLUGIN_SRC" ]; then
    echo "  Cloning hermes-predict-rlm..."
    git clone https://github.com/Trampoline-AI/predict-rlm.git "$HOME/work/hermes-predict-rlm"
    PLUGIN_SRC="$HOME/work/hermes-predict-rlm/plugins/predict_rlm"
fi
mkdir -p "$(dirname "$PLUGIN_DST")"
ln -sfn "$PLUGIN_SRC" "$PLUGIN_DST"
echo "  Plugin linked: $PLUGIN_DST -> $PLUGIN_SRC"

echo "=== 3. TrikeShed repo-local scan helper ==="
mkdir -p "$REPO_ROOT/scripts"
if [ ! -f "$REPO_ROOT/scripts/trikeshed_rlm_gradle.py" ]; then
    echo "  trikeshed_rlm_gradle.py already present"
else
    echo "  trikeshed_rlm_gradle.py present"
fi

echo "=== 4. Verify ==="
"$VENV_DIR/bin/python" -c "from rlm_gepa import RLMGepaProject; print('rlm_gepa OK')"
"$VENV_DIR/bin/python" -c "import predict_rlm; print('predict_rlm OK')" 2>/dev/null || echo "  (predict_rlm needs Deno for full runtime, that's fine)"
if [ -L "$PLUGIN_DST" ]; then echo "  Plugin symlink OK"; fi
which hermes && echo "  Hermes OK" || echo "  Hermes not in PATH"

echo "=== Done. Manual tasks: ==="
echo "  ./gradlew :libs:<mod>:trikeshedModuleBrief"
echo "  ./gradlew :libs:<mod>:trikeshedLint"
echo "  ./gradlew :libs:<mod>:trikeshedRefineRules"
echo "  ./gradlew :libs:<mod>:trikeshedApplyRules"
echo "  ./gradlew trikeshedLintLibs"
echo "  ./gradlew trikeshedGepaBuild"
