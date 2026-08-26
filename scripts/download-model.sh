#!/usr/bin/env bash
set -euo pipefail

# CMAKE lets you point at a bundled toolchain (e.g. the Android SDK's own copy) if plain
# `cmake` isn't on PATH: CMAKE=/path/to/sdk/cmake/<version>/bin/cmake ./download-model.sh
CMAKE="${CMAKE:-cmake}"

MODEL_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
WHISPER_CPP_DIR="third_party/whisper.cpp"
QUANTIZE_BUILD_DIR="$WHISPER_CPP_DIR/build-quantize-tool"
RAW_MODEL_DIR="$(mktemp -d)"
RAW_MODEL="$RAW_MODEL_DIR/ggml-small-f16.bin"
ANDROID_DEST="composeApp/src/androidMain/assets/models/ggml-small.bin"
IOS_DEST="iosApp/iosApp/Resources/ggml-small.bin"

mkdir -p "$(dirname "$ANDROID_DEST")" "$(dirname "$IOS_DEST")"

if [ ! -f "$ANDROID_DEST" ]; then
    curl -L "$MODEL_URL" -o "$RAW_MODEL"

    # Quantized to q8_0: roughly half the size and decode time of the raw fp16 model, with
    # output confirmed identical on whisper.cpp's own jfk.wav sample - see App.kt's on-screen
    # RTF metrics if you want to confirm on your own recordings too.
    "$CMAKE" -S "$WHISPER_CPP_DIR" -B "$QUANTIZE_BUILD_DIR" \
        -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF \
        -DCMAKE_BUILD_TYPE=Release
    "$CMAKE" --build "$QUANTIZE_BUILD_DIR" --target whisper-quantize -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
    "$QUANTIZE_BUILD_DIR/bin/whisper-quantize" "$RAW_MODEL" "$ANDROID_DEST" q8_0
fi

rm -rf "$RAW_MODEL_DIR"
cp "$ANDROID_DEST" "$IOS_DEST"

echo "Quantized model ready at $ANDROID_DEST and $IOS_DEST"
