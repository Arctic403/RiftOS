#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
WORK="$ROOT/.native-runtime"
LLAMA_REPO="${LLAMA_REPO:-https://github.com/ggml-org/llama.cpp.git}"
LLAMA_REF="${LLAMA_REF:-master}"
CODEX_URL="${CODEX_URL:-https://github.com/openai/codex/releases/latest/download/codex-aarch64-unknown-linux-musl.tar.gz}"

: "${ANDROID_NDK_ROOT:?ANDROID_NDK_ROOT must point to the Android NDK}"

rm -rf "$WORK" "$OUT/arm64-v8a" "$OUT/armeabi-v7a"
mkdir -p "$WORK" "$OUT/arm64-v8a" "$OUT/armeabi-v7a"

git clone --depth 1 --branch "$LLAMA_REF" "$LLAMA_REPO" "$WORK/llama.cpp"

build_llama_server() {
  local abi="$1"
  local build="$WORK/llama-$abi"
  cmake -S "$WORK/llama.cpp" -B "$build" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=ON \
    -DLLAMA_BUILD_UI=OFF
  cmake --build "$build" --config Release --target llama-server -j"$(nproc)"
  install -m 0755 "$build/bin/llama-server" "$OUT/$abi/libllamaserver.so"
}

build_llama_server arm64-v8a
build_llama_server armeabi-v7a

# OpenAI currently publishes Codex CLI for Linux ARM64, not ARMv7.
# Naming it libcodex.so lets Android package/extract it into nativeLibraryDir,
# which remains executable under Android 10+ W^X rules.
mkdir -p "$WORK/codex"
curl --fail --location --retry 3 "$CODEX_URL" -o "$WORK/codex/codex.tar.gz"
tar -xzf "$WORK/codex/codex.tar.gz" -C "$WORK/codex"
CODEX_BIN="$(find "$WORK/codex" -maxdepth 1 -type f -name 'codex-aarch64-unknown-linux-musl' -print -quit)"
if [[ -z "$CODEX_BIN" ]]; then
  echo "Codex ARM64 binary not found in release archive" >&2
  exit 1
fi
install -m 0755 "$CODEX_BIN" "$OUT/arm64-v8a/libcodex.so"

for abi in arm64-v8a armeabi-v7a; do
  test -s "$OUT/$abi/libllamaserver.so"
done
test -s "$OUT/arm64-v8a/libcodex.so"

echo "Prepared RiftOS native runtime:"
find "$OUT" -maxdepth 2 -type f -printf '%P %s bytes\n' | sort
