#!/usr/bin/env bash
set -euo pipefail

# Converts the fine-tuned clinical Whisper checkpoint into the ggml format whisper.cpp
# loads, mirroring download-model.sh. Unlike that script there is nothing to download -
# the checkpoint is an HF safetensors export from the medical-data-sources project that
# you copy into place first:
#
#   cp -r <medical-data-sources>/data/finetune/whisper-small-clinical-de models-src/
#   ./scripts/build-clinical-model.sh
#
# PYTHON / CMAKE let you point at specific toolchains if the defaults aren't on PATH
# (e.g. CMAKE=$ANDROID_HOME/cmake/<version>/bin/cmake, as with download-model.sh).
PYTHON="${PYTHON:-$(command -v python3 || command -v python || true)}"
CMAKE="${CMAKE:-cmake}"

CHECKPOINT_DIR="models-src/whisper-small-clinical-de"
WHISPER_CPP_DIR="third_party/whisper.cpp"
CONVERT_SCRIPT="$WHISPER_CPP_DIR/models/convert-h5-to-ggml.py"
QUANTIZE_BUILD_DIR="$WHISPER_CPP_DIR/build-quantize-tool"
WORK_DIR="$(mktemp -d)"
F16_MODEL="$WORK_DIR/ggml-small-clinical-de-f16.bin"
ANDROID_DEST="composeApp/src/androidMain/assets/models/ggml-small-clinical-de.bin"
IOS_DEST="iosApp/iosApp/Resources/ggml-small-clinical-de.bin"

trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$(dirname "$ANDROID_DEST")" "$(dirname "$IOS_DEST")"

if [ ! -f "$ANDROID_DEST" ]; then
    if [ ! -d "$CHECKPOINT_DIR" ]; then
        echo "error: $CHECKPOINT_DIR not found - copy the fine-tuned checkpoint there first:" >&2
        echo "  cp -r <medical-data-sources>/data/finetune/whisper-small-clinical-de models-src/" >&2
        exit 1
    fi
    if [ -z "$PYTHON" ]; then
        echo "error: no python3/python on PATH (needed for the ggml conversion)." >&2
        exit 1
    fi

    if [ ! -f "$CONVERT_SCRIPT" ]; then
        git submodule update --init "$WHISPER_CPP_DIR"
    fi

    # convert-h5-to-ggml.py reads whisper/assets/mel_filters.npz from an openai/whisper
    # checkout; fetch just that one file rather than cloning the whole repo. --fail so an
    # HTTP error page doesn't get saved as if it were the npz (curl exits 0 without it).
    mkdir -p "$WORK_DIR/whisper/whisper/assets"
    curl --fail -L \
        "https://raw.githubusercontent.com/openai/whisper/main/whisper/assets/mel_filters.npz" \
        -o "$WORK_DIR/whisper/whisper/assets/mel_filters.npz"

    # f16 ggml conversion - pure Python, needs torch + transformers (the same versions
    # the checkpoint was exported with; see its config.json "transformers_version").
    # Writes $WORK_DIR/ggml-model.bin.
    "$PYTHON" "$CONVERT_SCRIPT" "$CHECKPOINT_DIR" "$WORK_DIR/whisper" "$WORK_DIR"
    mv "$WORK_DIR/ggml-model.bin" "$F16_MODEL"

    # q8_0 quantization: roughly half the size and decode time, output confirmed
    # identical for the stock model (see download-model.sh). Needs whisper-quantize built
    # from the vendored whisper.cpp via cmake + a C/C++ toolchain. Without that toolchain
    # available, ship the f16 model instead - whisper.cpp loads it fine, it's just
    # ~465 MiB vs ~252 MiB. Re-run this script on a machine with cmake to swap in q8_0.
    if "$CMAKE" --version >/dev/null 2>&1; then
        "$CMAKE" -S "$WHISPER_CPP_DIR" -B "$QUANTIZE_BUILD_DIR" \
            -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF \
            -DCMAKE_BUILD_TYPE=Release
        "$CMAKE" --build "$QUANTIZE_BUILD_DIR" --target whisper-quantize -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
        "$QUANTIZE_BUILD_DIR/bin/whisper-quantize" "$F16_MODEL" "$ANDROID_DEST" q8_0
    else
        echo "warning: '$CMAKE' not found - shipping the unquantized f16 model (~465 MiB)." >&2
        echo "Install cmake + a C/C++ toolchain and re-run to produce the q8_0 model." >&2
        cp "$F16_MODEL" "$ANDROID_DEST"
    fi
fi

cp "$ANDROID_DEST" "$IOS_DEST"

echo "Clinical model ready at $ANDROID_DEST and $IOS_DEST"
