#!/usr/bin/env bash
set -euo pipefail

MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
ANDROID_DEST="composeApp/src/androidMain/assets/models/ggml-small.bin"
IOS_DEST="iosApp/iosApp/Resources/ggml-small.bin"

mkdir -p "$(dirname "$ANDROID_DEST")" "$(dirname "$IOS_DEST")"

if [ ! -f "$ANDROID_DEST" ]; then
    curl -L "$MODEL_URL" -o "$ANDROID_DEST"
fi

cp "$ANDROID_DEST" "$IOS_DEST"

echo "Model ready at $ANDROID_DEST and $IOS_DEST"
