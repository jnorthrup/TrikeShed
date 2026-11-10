#!/bin/sh
# One-time online setup for utils/stanza, run before any air-gapped pass.
# Stanza gets its own venv so Jeva's pinned torch, numpy and transformers never move.
set -eu
ROOT="${TRIKESHED_STANZA_HOME:-$HOME/.local/share/TrikeShed/stanza}"
WN="${TRIKESHED_WN_EXTRA:-$HOME/.local/share/TrikeShed/sumo-wordnet}"
REF=25bb366fdffd8769e741d2e22f215c2785bddce1   # utils/sumo/sumo-full-corpus.pins
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$ROOT/resources" "$WN"
[ -x "$ROOT/venv/bin/python" ] || uv venv -q --python 3.12 "$ROOT/venv"
uv pip install -q --python "$ROOT/venv/bin/python" -r "$HERE/requirements.txt"
"$ROOT/venv/bin/python" - "$ROOT" <<'EOF'
import sys, stanza, nltk
root = sys.argv[1]
stanza.download('en', model_dir=f'{root}/resources', package='default',
                processors='tokenize,mwt,pos,lemma,depparse,ner', logging_level='WARN')
nltk.download('wordnet', download_dir=f'{root}/nltk_data', quiet=True)
print('stanza', stanza.__version__, 'models and WordNet 3.0 in', root)
EOF
for f in WordNetMappings30-verb.txt WordNetMappings30-adj.txt; do
  [ -s "$WN/$f" ] || curl -sS -f -m 120 -o "$WN/$f" "https://raw.githubusercontent.com/ontologyportal/sumo/$REF/WordNetMappings/$f"
done
{ echo "# ontologyportal/sumo ref $REF"; shasum -a 256 "$WN"/WordNetMappings30-*.txt; } > "$WN/pins.txt"
cat "$WN/pins.txt"
echo "SUMO KIF and the noun mapping come from ./gradlew fetchSumoCorpus fetchSumoFullCorpus (build/generated/...)."
