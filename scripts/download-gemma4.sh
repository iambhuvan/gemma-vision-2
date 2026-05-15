#!/usr/bin/env bash
# Download the Gemma 4 model files referenced by MediaPipeProvider.kt
# into app/src/main/assets/. Idempotent — re-runs are no-ops if files
# already exist with the right size.

set -euo pipefail

REPO="${REPO:-litert-community/gemma-4-E4B-it-litert-lm}"
MODEL_FILE="${MODEL_FILE:-gemma-4-E4B-it.litertlm}"

cd "$(dirname "$0")/.."          # project root
ASSETS=app/src/main/assets
mkdir -p "$ASSETS"

echo "Downloading $REPO into $ASSETS ..."
echo "(this is ~2-3 GB; takes a few minutes on a fast connection)"

if ! command -v huggingface-cli >/dev/null 2>&1; then
    echo "huggingface-cli not found. Install with: pip install -U huggingface_hub"
    exit 1
fi

huggingface-cli download "$REPO" \
    "$MODEL_FILE" \
    chat_template.jinja \
    --local-dir "$ASSETS"

# Sanity check: any healthy E4B INT4 file is > 1 GB.
ACTUAL_BYTES=$(stat -f%z "$ASSETS/$MODEL_FILE" 2>/dev/null || stat -c%s "$ASSETS/$MODEL_FILE")
if [ "$ACTUAL_BYTES" -lt 500000000 ]; then
    echo "ERROR: $MODEL_FILE is only $ACTUAL_BYTES bytes — likely corrupted."
    echo "Delete it and re-run."
    exit 1
fi

echo ""
echo "Done. Files in $ASSETS:"
ls -lh "$ASSETS"
