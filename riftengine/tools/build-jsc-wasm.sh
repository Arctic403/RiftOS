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
  -DCMAKE_EXE_LINKER_FLAGS="-sEXPORTED_RUNTIME_METHODS=['callMain']" \
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

# Smoke-test the generated JSC runtime itself. The Node-hosted Emscripten shell
# has a virtual filesystem, so a normal host path is not automatically visible
# to JSC. Executing source with -e avoids confusing filesystem mounting with
# engine correctness and proves that the generated wasm boots and runs JS.
set +e
node "$ROOT/riftengine/jsc-dist/jsc.js" -e 'print("RIFT_JSC_SMOKE=" + (20 + 22));' > "$LOGDIR/jsc-smoke.log" 2>&1
smoke_rc=$?
set -e
if [ "$smoke_rc" -ne 0 ] || ! grep -q 'RIFT_JSC_SMOKE=42' "$LOGDIR/jsc-smoke.log"; then
  echo "error: JavaScriptCore smoke test failed" >&2
  cat "$LOGDIR/jsc-smoke.log" || true
  exit 5
fi

# The browser worker needs a reusable entry point after the first cold boot.
# Emscripten only places runtime helpers on Module when they are explicitly
# exported, so fail the build if callMain was optimized away.
if ! grep -q 'Module\["callMain"\]' "$ROOT/riftengine/jsc-dist/jsc.js"; then
  echo "error: JSC build does not expose Module.callMain for warm re-entry" >&2
  exit 6
fi

echo "RIFT_JSC_CALLMAIN=exported" | tee "$LOGDIR/jsc-callmain.txt"
cat "$LOGDIR/jsc-smoke.log"
ls -lh "$ROOT/riftengine/jsc-dist/jsc.js" "$ROOT/riftengine/jsc-dist/jsc.wasm"
echo "RiftEngine Phase 1: real JavaScriptCore executed JavaScript inside wasm."
echo "RiftEngine Phase 2 compatibility: Module.callMain is exported for warm worker re-entry."
