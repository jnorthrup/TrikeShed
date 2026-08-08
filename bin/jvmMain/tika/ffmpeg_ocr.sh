#!/bin/bash
set -euo pipefail

# FFmpeg OCR preprocessing — ported from jnorthrup/tika4all (no memvid).
# Invoked by Tika's TesseractOCRParser via <imageProcessingCommand>:
#   ffmpeg_ocr.sh <input_image> <output_image>
# Enhances scans/photos before OCR: grayscale + contrast/brightness equalization.

if [ "$#" -ne 2 ]; then
    echo "Usage: ffmpeg_ocr.sh <input_image> <output_image>" >&2
    exit 1
fi

input=$1
output=$2

if [ ! -f "$input" ]; then
    echo "Input image not found: $input" >&2
    exit 1
fi

filter_params="format=gray,eq=contrast=1.5:brightness=0.1:gamma=1.0:saturation=0.0"

ffmpeg -i "$input" -vf "$filter_params" "$output" -y 2>/dev/null || {
    echo "FFmpeg processing failed with parameters: $filter_params" >&2
    exit 1
}

echo "Enhanced image: $output" >&2
