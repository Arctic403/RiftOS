#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <WebkitWasm helper checkout>" >&2
  exit 2
fi

RIFTOS_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HELPER_ROOT="$(cd "$1" && pwd)"
TP="$HELPER_ROOT/third_party"
SYSROOT="$TP/wasm-sysroot"
BUILD="$HELPER_ROOT/build/riftwebkit-probe"
FSROOT="$HELPER_ROOT/build/riftwebkit-probe-fs"

if [ ! -f "$TP/emsdk/emsdk_env.sh" ] || [ ! -d "$TP/WebKit" ] || [ ! -d "$SYSROOT" ]; then
  echo "RiftWebKit prerequisites are missing; run the pinned helper bootstrap first." >&2
  exit 3
fi

# shellcheck disable=SC1091
source "$TP/emsdk/emsdk_env.sh" >/dev/null 2>&1

command -v emcc >/dev/null
command -v emcmake >/dev/null
command -v ninja >/dev/null
emcc --version | head -1

ICU_DATA="$SYSROOT/share/icu/77.1/icudt77l.dat"
test -s "$ICU_DATA"

# Minimal runtime font tree. The sysroot conf.d entries are install-root
# symlinks, so stage real files for Emscripten's --embed-file packager.
rm -rf "$FSROOT"
mkdir -p "$FSROOT/etc-fonts/conf.d" "$FSROOT/fonts"
cp "$SYSROOT/etc/fonts/fonts.conf" "$FSROOT/etc-fonts/fonts.conf"
for entry in "$SYSROOT/etc/fonts/conf.d/"*.conf; do
  [ -e "$entry" ] || continue
  source_file="$SYSROOT/share/fontconfig/conf.avail/$(basename "$entry")"
  [ -f "$source_file" ] && cp "$source_file" "$FSROOT/etc-fonts/conf.d/"
done
cp /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf "$FSROOT/fonts/DejaVuSans.ttf"
test -s "$FSROOT/etc-fonts/fonts.conf"
test -s "$FSROOT/fonts/DejaVuSans.ttf"
test -n "$(find "$FSROOT/etc-fonts/conf.d" -type f -name '*.conf' -print -quit)"

EMBEDDER_CMAKE="$RIFTOS_ROOT/engines/webkit-poc/riftwebkit-embedder.cmake"
test -s "$EMBEDDER_CMAKE"
test -s "$RIFTOS_ROOT/engines/webkit-poc/riftwebkit-probe.cpp"

rm -rf "$BUILD"
mkdir -p "$BUILD"

set +e
emcmake cmake -S "$TP/WebKit" -B "$BUILD" -GNinja \
  -DPORT=Emscripten \
  -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_JIT=OFF \
  -DENABLE_C_LOOP=ON \
  -DENABLE_STATIC_JSC=ON \
  -DUSE_SYSTEM_MALLOC=ON \
  -DICU_ROOT="$SYSROOT" \
  -DCMAKE_FIND_ROOT_PATH="$SYSROOT" \
  -DJSC_EMBED_ICU_DATA_FILE="$ICU_DATA" \
  -DEMSCRIPTEN_EMBEDDER_CMAKE="$EMBEDDER_CMAKE" \
  -DRIFTWEBKIT_ICU_DATA_FILE="$ICU_DATA" \
  -DRIFTWEBKIT_FONTCONFIG_ETC_DIR="$FSROOT/etc-fonts" \
  -DRIFTWEBKIT_FONTS_DIR="$FSROOT/fonts" \
  "-DCMAKE_C_FLAGS=-msimd128" \
  "-DCMAKE_CXX_FLAGS=-msimd128" \
  > "$HELPER_ROOT/build/riftwebkit-configure.log" 2>&1
configure_status=$?
set -e
if [ "$configure_status" -ne 0 ]; then
  echo "=== RiftWebKit configure failed ===" >&2
  tail -n 160 "$HELPER_ROOT/build/riftwebkit-configure.log" >&2 || true
  exit "$configure_status"
fi

echo "RIFTWEBKIT CONFIGURE: OK"

jobs="${RIFTWEBKIT_JOBS:-4}"
set +e
ninja -C "$BUILD" -j "$jobs" -k 30 WebCore RiftWebKitProbe \
  > "$HELPER_ROOT/build/riftwebkit-ninja.log" 2>&1
build_status=$?
set -e
if [ "$build_status" -ne 0 ]; then
  echo "=== RiftWebKit ninja failed ===" >&2
  grep -n 'error:' "$HELPER_ROOT/build/riftwebkit-ninja.log" | tail -n 80 >&2 || true
  tail -n 160 "$HELPER_ROOT/build/riftwebkit-ninja.log" >&2 || true
  exit "$build_status"
fi

echo "RIFTWEBKIT NINJA: OK"

JS_FILE="$(find "$BUILD" -type f -name 'riftwebkit.js' -print -quit)"
WASM_FILE="$(find "$BUILD" -type f -name 'riftwebkit.wasm' -print -quit)"
test -n "$JS_FILE" && test -s "$JS_FILE"
test -n "$WASM_FILE" && test -s "$WASM_FILE"

mkdir -p "$RIFTOS_ROOT/.riftwebkit-output"
cp "$JS_FILE" "$RIFTOS_ROOT/.riftwebkit-output/riftwebkit.js"
cp "$WASM_FILE" "$RIFTOS_ROOT/.riftwebkit-output/riftwebkit.wasm"

printf 'JS:   '; du -h "$RIFTOS_ROOT/.riftwebkit-output/riftwebkit.js" | cut -f1
printf 'WASM: '; du -h "$RIFTOS_ROOT/.riftwebkit-output/riftwebkit.wasm" | cut -f1
