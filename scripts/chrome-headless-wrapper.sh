#!/bin/sh

# Locate a Chrome/Chromium executable from common locations and run it with the required flags
CANDIDATES=(
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
  "/Applications/Chromium.app/Contents/MacOS/Chromium"
  "/usr/bin/google-chrome-stable"
  "/usr/bin/google-chrome"
  "/usr/bin/chromium-browser"
  "/usr/bin/chromium"
)

CHROME_BIN=""
for p in "${CANDIDATES[@]}"; do
  if [ -x "$p" ]; then
    CHROME_BIN="$p"
    break
  fi
done

if [ -z "$CHROME_BIN" ]; then
  echo "ERROR: Chrome/Chromium not found in common locations." 1>&2
  echo "Please install Chrome or adjust the wrapper to point at your Chrome binary." 1>&2
  exit 2
fi

exec "$CHROME_BIN" --headless --no-sandbox --disable-gpu --disable-dev-shm-usage --remote-debugging-port=9222 "$@"
