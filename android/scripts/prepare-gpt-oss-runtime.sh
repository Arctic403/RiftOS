#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
WORK="$ROOT/.gpt-oss-runtime"
LLAMA_REPO="${LLAMA_REPO:-https://github.com/ggml-org/llama.cpp.git}"
LLAMA_REF="${LLAMA_REF:-304665fe7ac957df95e3ff8c8c4ffdf92dd6ffa3}"

if [[ -z "${ANDROID_NDK_ROOT:-}" ]]; then
  SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$SDK_ROOT" && -d "$SDK_ROOT/ndk" ]]; then
    ANDROID_NDK_ROOT="$(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
  fi
fi
: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must point to an installed Android NDK}"

test -f "$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake"
rm -rf "$WORK" "$OUT/arm64-v8a" "$OUT/x86_64"
mkdir -p "$WORK/llama.cpp" "$OUT/arm64-v8a" "$OUT/x86_64"

git -C "$WORK/llama.cpp" init -q
git -C "$WORK/llama.cpp" remote add origin "$LLAMA_REPO"
git -C "$WORK/llama.cpp" fetch --depth=1 origin "$LLAMA_REF"
git -C "$WORK/llama.cpp" checkout --detach FETCH_HEAD
ACTUAL_REF="$(git -C "$WORK/llama.cpp" rev-parse HEAD)"
[[ "$ACTUAL_REF" == "$LLAMA_REF" ]]

build_server() {
  local abi="$1"
  local build="$WORK/build-$abi"
  cmake -S "$WORK/llama.cpp" -B "$build" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_VULKAN=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_UI=OFF
  cmake --build "$build" --config Release --target llama-server -j"$(nproc)"
  test -s "$build/bin/llama-server"
  install -m 0755 "$build/bin/llama-server" "$OUT/$abi/libllamaserver.so"
}

build_server arm64-v8a
build_server x86_64

for abi in arm64-v8a x86_64; do
  test -s "$OUT/$abi/libllamaserver.so"
  file "$OUT/$abi/libllamaserver.so"
done

printf 'Prepared RiftOS gpt-oss runtime from llama.cpp %s\n' "$LLAMA_REF"
find "$OUT" -maxdepth 2 -type f -name 'libllamaserver.so' -printf '%P %s bytes\n' | sort
