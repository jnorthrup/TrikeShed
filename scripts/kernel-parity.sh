#!/usr/bin/env bash
# Regenerate doc/kernel/parity.tsv: every Kotlin file the CCEKCMMKPlatform
# repository holds, against this repository's copy at the same path (or its
# moved path). KernelParityTest holds the working tree to the result.
#
#   scripts/kernel-parity.sh                 # clones the pinned platform commit
#   scripts/kernel-parity.sh ~/work/ccek     # uses an existing clone
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM_URL="https://github.com/jnorthrup/CCEKCMMKPlatform"
PLATFORM_COMMIT="f3f276eeb2148c649e1d6ac5fa2768cdfbdc2821"
OUT="$REPO/doc/kernel/parity.tsv"

if [ $# -ge 1 ]; then
  P="$1"
else
  P="$(mktemp -d "${TMPDIR:-/tmp}/ccek-platform.XXXXXX")"
  git clone -q "$PLATFORM_URL" "$P"
  git -C "$P" checkout -q "$PLATFORM_COMMIT"
fi
HEAD_SHA="$(git -C "$P" rev-parse HEAD)"

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

{
  printf '# CCEKCMMKPlatform kernel parity — platform %s (%s)\n' "$HEAD_SHA" "$PLATFORM_URL"
  printf '# regenerate: scripts/kernel-parity.sh ; gate: KernelParityTest\n'
  printf 'path\tstatus\tplatformSha256\ttrikeshedSha256\tresolvedPath\n'
  (cd "$P" && find src -name '*.kt' | sort) | while read -r rel; do
    psha="$(sha "$P/$rel")"
    if [ -f "$REPO/$rel" ]; then
      tsha="$(sha "$REPO/$rel")"
      if [ "$psha" = "$tsha" ]; then status=identical; else status=ahead; fi
      printf '%s\t%s\t%s\t%s\t%s\n' "$rel" "$status" "$psha" "$tsha" "$rel"
    else
      base="$(basename "$rel")"
      # a moved file: same basename elsewhere under src/, preferring commonMain
      cand="$(cd "$REPO" && find src -name "$base" -not -path '*/build/*' | grep -v "^$rel$" | sort | head -1 || true)"
      if [ -n "$cand" ]; then
        tsha="$(sha "$REPO/$cand")"
        printf '%s\t%s\t%s\t%s\t%s\n' "$rel" "moved:$cand" "$psha" "$tsha" "$cand"
      else
        printf '%s\t%s\t%s\t%s\t%s\n' "$rel" "absent" "$psha" "" ""
      fi
    fi
  done
} > "$OUT"
printf 'wrote %s (%s rows)\n' "$OUT" "$(grep -vc '^#' "$OUT")"
