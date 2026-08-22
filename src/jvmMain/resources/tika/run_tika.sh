#!/usr/bin/env bash
# run_tika.sh — text out of anything: PDF/DOCX/images → stdout as text, via Apache Tika + Tesseract,
# with the ffmpeg pre-pass (grayscale + contrast/brightness) on images before OCR.
#
#   run_tika.sh FILE...            text of each file (images: ffmpeg → tesseract; PDFs: Tika OCR_STRATEGY auto)
#   run_tika.sh -m FILE            metadata (JSON)
#   run_tika.sh --list-parsers     what this Tika can parse
#
# One jar (tika-app, all parsers inside), cached in ~/.cache/tika. Tika 3 has no image-preprocess hook
# other than ImageMagick, so the ffmpeg pass is done HERE, not in tika-config.xml.
set -euo pipefail

TIKA_VERSION=${TIKA_VERSION:-3.2.3}
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/tika
JAR=$CACHE/tika-app-$TIKA_VERSION.jar
CONF=$CACHE/tika-config-$TIKA_VERSION.xml
FILTER=${FFMPEG_OCR_FILTER:-format=gray,eq=contrast=1.5:brightness=0.1:gamma=1.0:saturation=0.0}
TESSERACT=${TESSERACT:-$(command -v tesseract || true)}

need() { command -v "$1" >/dev/null || { echo "missing: $1" >&2; exit 127; }; }
need java; need ffmpeg; [ -n "$TESSERACT" ] || echo "warning: tesseract not found; images/scans yield no text" >&2

mkdir -p "$CACHE"
[ -s "$JAR" ] || curl -fsSL -o "$JAR" "https://repo1.maven.org/maven2/org/apache/tika/tika-app/$TIKA_VERSION/tika-app-$TIKA_VERSION.jar"
# Real Tika 3 config schema: parser params. OCR on; no ImageMagick pre-pass (ffmpeg does it); PDFs OCR when the text layer is thin.
cat > "$CONF" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<properties>
  <parsers>
    <parser class="org.apache.tika.parser.DefaultParser"/>
    <parser class="org.apache.tika.parser.ocr.TesseractOCRParser">
      <params>
        <param name="tesseractPath" type="string">$(dirname "${TESSERACT:-/usr/bin/tesseract}")</param>
        <param name="language" type="string">eng</param>
        <param name="enableImagePreprocessing" type="bool">false</param>
      </params>
    </parser>
    <parser class="org.apache.tika.parser.pdf.PDFParser">
      <params>
        <param name="ocrStrategy" type="string">auto</param>
        <param name="extractInlineImages" type="bool">false</param>
      </params>
    </parser>
  </parsers>
</properties>
XML

tika() { java -jar "$JAR" --config="$CONF" "$@"; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

case "${1:-}" in
  --list-parsers) tika --list-parsers; exit ;;
  -m) shift; tika -j "$1"; exit ;;
  ""|-h|--help) sed -n '2,8p' "$0" >&2; exit 1 ;;
esac

for f in "$@"; do
  case "${f##*.}" in
    png|jpg|jpeg|tif|tiff|bmp|gif|webp|heic)   # the pre-pass, then OCR the conditioned image
      out=$TMP/$(basename "${f%.*}").png
      ffmpeg -y -loglevel error -i "$f" -vf "$FILTER" "$out"
      tika -t "$out" ;;
    *) tika -t "$f" ;;
  esac
done
