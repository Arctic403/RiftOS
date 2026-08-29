#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/riftengine/config/pins.env"
WORK="${RIFTENGINE_WORK:-$ROOT/.riftengine-work}"
WEBKIT="$WORK/WebKit"
SYSROOT="$WORK/sysroot"
BUILD="$WORK/build-jsc"
LOGDIR="$ROOT/riftengine/logs"
mkdir -p "$LOGDIR" "$BUILD"

command -v emcmake >/dev/null 2>&1 || { echo "error: Emscripten environment is not active" >&2; exit 2; }

bash "$ROOT/riftengine/tools/build-icu-wasm.sh" 2>&1 | tee "$LOGDIR/icu.log"
bash "$ROOT/riftengine/tools/prepare-webkit.sh" 2>&1 | tee "$LOGDIR/prepare-webkit.log"

ICU_DATA="$(find "$SYSROOT/share/icu" -type f -name 'icudt*.dat' -print -quit 2>/dev/null || true)"
if [ -z "$ICU_DATA" ]; then
  echo "error: ICU data archive missing" >&2
  exit 3
fi

rm -rf "$BUILD"
mkdir -p "$BUILD"

echo "==> configure JavaScriptCore JSCOnly for wasm32"
set +e
emcmake cmake -S "$WEBKIT" -B "$BUILD" -GNinja \
  -DPORT=JSCOnly \
  -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_JIT=OFF \
  -DENABLE_C_LOOP=ON \
  -DENABLE_STATIC_JSC=ON \
  -DENABLE_REMOTE_INSPECTOR=OFF \
  -DENABLE_SAMPLING_PROFILER=OFF \
  -DENABLE_WEBASSEMBLY=OFF \
  -DUSE_GLIB=OFF \
  -DUSE_LIBBACKTRACE=OFF \
  -DUSE_SYSTEM_MALLOC=ON \
  -DICU_ROOT="$SYSROOT" \
  -DCMAKE_PREFIX_PATH="$SYSROOT" \
  -DCMAKE_FIND_ROOT_PATH="$SYSROOT" \
  -DJSC_EMBED_ICU_DATA_FILE="$ICU_DATA" \
  > "$LOGDIR/jsc-configure.log" 2>&1
configure_rc=$?
set -e

if [ "$configure_rc" -ne 0 ]; then
  echo "JSC configure failed; tail follows"
  tail -n 160 "$LOGDIR/jsc-configure.log" || true
  exit "$configure_rc"
fi

echo "==> build JavaScriptCore shell"
set +e
ninja -C "$BUILD" -k 30 -j"${RIFT_JOBS:-2}" jsc > "$LOGDIR/jsc-build.log" 2>&1
build_rc=$?
set -e

if [ "$build_rc" -ne 0 ]; then
  echo "JSC build failed; condensed errors follow"
  grep -nE 'FAILED:|error:|undefined symbol|CMake Error|ninja: build stopped' "$LOGDIR/jsc-build.log" | tail -n 120 || true
  echo "--- tail ---"
  tail -n 160 "$LOGDIR/jsc-build.log" || true
  exit "$build_rc"
fi

JSC_JS="$(find "$BUILD" -type f -name 'jsc.js' -print -quit 2>/dev/null || true)"
JSC_WASM="$(find "$BUILD" -type f -name 'jsc.wasm' -print -quit 2>/dev/null || true)"
if [ -z "$JSC_JS" ] || [ -z "$JSC_WASM" ]; then
  echo "error: build succeeded but jsc.js/jsc.wasm were not found" >&2
  find "$BUILD" -maxdepth 4 -type f -name 'jsc*' -ls || true
  exit 4
fi

mkdir -p "$ROOT/riftengine/jsc-dist"
cp "$JSC_JS" "$ROOT/riftengine/jsc-dist/jsc.js"
cp "$JSC_WASM" "$ROOT/riftengine/jsc-dist/jsc.wasm"

printf 'print("RIFT_JSC_SMOKE=" + (20 + 22));\n' > "$ROOT/riftengine/jsc-dist/smoke.js"

# Node is only a CI smoke host here; the produced wasm remains browser-targeted
# groundwork for the RiftEngine bridge.
set +e
node "$ROOT/riftengine/jsc-dist/jsc.js" "$ROOT/riftengine/jsc-dist/smoke.js" > "$LOGDIR/jsc-smoke.log" 2>&1
smoke_rc=$?
set -e
if [ "$smoke_rc" -ne 0 ] || ! grep -q 'RIFT_JSC_SMOKE=42' "$LOGDIR/jsc-smoke.log"; then
  echo "error: JavaScriptCore smoke test failed" >&2
  cat "$LOGDIR/jsc-smoke.log" || true
  exit 5
fi

cat "$LOGDIR/jsc-smoke.log"
ls -lh "$ROOT/riftengine/jsc-dist/jsc.js" "$ROOT/riftengine/jsc-dist/jsc.wasm"
echo "RiftEngine Phase 1: real JavaScriptCore executed JavaScript inside wasm."
