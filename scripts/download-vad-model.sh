#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
ANDROID_DEST="composeApp/src/androidMain/assets/models/ggml-silero-v6.2.0.bin"
IOS_DEST="iosApp/iosApp/Resources/ggml-silero-v6.2.0.bin"

mkdir -p "$(dirname "$ANDROID_DEST")" "$(dirname "$IOS_DEST")"

if [ ! -f "$ANDROID_DEST" ]; then
    # Download to a temp file and move into place only on success - curl without --fail exits 0
    # even on an HTTP error page, and downloading straight to ANDROID_DEST would leave that error
    # page in place as if it were the model, permanently skipped by the exists-check above on
    # every later run.
    TMP_DEST="$(mktemp)"
    curl --fail -L "$MODEL_URL" -o "$TMP_DEST"
    mv "$TMP_DEST" "$ANDROID_DEST"
fi

cp "$ANDROID_DEST" "$IOS_DEST"

echo "VAD model ready at $ANDROID_DEST and $IOS_DEST"
