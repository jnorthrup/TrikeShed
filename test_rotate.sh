#!/bin/bash
LOG_DIR="./logs"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/oroboros-daemon.log"

if [[ -f "$LOG_FILE" ]]; then
    for i in {4..1}; do
        if [[ -f "$LOG_FILE.$i" ]]; then
            mv "$LOG_FILE.$i" "$LOG_FILE.$((i+1))"
        fi
    done
    mv "$LOG_FILE" "$LOG_FILE.1"
fi
echo "test" > "$LOG_FILE"
