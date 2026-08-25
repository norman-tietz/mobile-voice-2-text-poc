#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin"
ANDROID_DEST="composeApp/src/androidMain/assets/models/ggml-silero-v6.2.0.bin"
IOS_DEST="iosApp/iosApp/Resources/ggml-silero-v6.2.0.bin"

mkdir -p "$(dirname "$ANDROID_DEST")" "$(dirname "$IOS_DEST")"

if [ ! -f "$ANDROID_DEST" ]; then
    curl -L "$MODEL_URL" -o "$ANDROID_DEST"
fi

cp "$ANDROID_DEST" "$IOS_DEST"

echo "VAD model ready at $ANDROID_DEST and $IOS_DEST"
