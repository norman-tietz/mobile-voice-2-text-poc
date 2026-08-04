#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../third_party/whisper.cpp"

cmake -B build-ios-device -G Xcode \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY="" \
    -DBUILD_SHARED_LIBS=OFF \
    -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF
cmake --build build-ios-device --config Release

cmake -B build-ios-sim -G Xcode \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT=iphonesimulator \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY="" \
    -DBUILD_SHARED_LIBS=OFF \
    -DWHISPER_BUILD_EXAMPLES=OFF -DWHISPER_BUILD_TESTS=OFF
cmake --build build-ios-sim --config Release

echo "Device lib:    third_party/whisper.cpp/build-ios-device/src/Release-iphoneos/libwhisper.a"
echo "Simulator lib: third_party/whisper.cpp/build-ios-sim/src/Release-iphonesimulator/libwhisper.a"