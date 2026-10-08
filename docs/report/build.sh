#!/usr/bin/env bash
# Renders principles-and-practices.html to AMRAP-Principles-and-Practices.pdf with headless Chrome.
set -euo pipefail

cd "$(dirname "$0")"

chrome=""
for c in google-chrome google-chrome-stable chromium chromium-browser; do
  if command -v "$c" >/dev/null 2>&1; then chrome="$c"; break; fi
done
if [[ -z "$chrome" ]]; then
  echo "error: google-chrome or chromium is required" >&2
  exit 1
fi

out="AMRAP-Principles-and-Practices.pdf"
"$chrome" --headless --disable-gpu --no-sandbox --no-pdf-header-footer \
  --print-to-pdf="$PWD/$out" "file://$PWD/principles-and-practices.html" 2>/dev/null

if command -v pdfinfo >/dev/null 2>&1; then
  echo "$out: $(pdfinfo "$out" | awk '/^Pages:/ {print $2}') pages"
else
  echo "wrote $out"
fi
