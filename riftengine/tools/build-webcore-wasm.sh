#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/riftengine/config/pins.env"
WORK="${RIFTENGINE_WORK:-/tmp/riftengine-webcore}"
WEBKIT="$WORK/WebKit"
SYSROOT="$WORK/sysroot"
BUILD="$WORK/build-webcore"
LOGDIR="$ROOT/riftengine/logs/webcore"
mkdir -p "$LOGDIR" "$BUILD"

command -v emcmake >/dev/null 2>&1 || { echo 'error: Emscripten environment is not active' >&2; exit 2; }

# Reuse the same pinned ICU/WebKit preparation that already produced our
# on-device persistent JavaScriptCore build. WebCore additionally needs its
# platform libraries cross-compiled into the same wasm sysroot.
bash "$ROOT/riftengine/tools/build-icu-wasm.sh" 2>&1 | tee "$LOGDIR/icu.log"
bash "$ROOT/riftengine/tools/build-libxml2-wasm.sh" 2>&1 | tee "$LOGDIR/libxml2.log"
bash "$ROOT/riftengine/tools/build-sqlite-wasm.sh" 2>&1 | tee "$LOGDIR/sqlite.log"
bash "$ROOT/riftengine/tools/prepare-webkit.sh" 2>&1 | tee "$LOGDIR/prepare-webkit.log"
python3 "$ROOT/riftengine/tools/port-webcore-emscripten.py" "$WEBKIT" 2>&1 | tee "$LOGDIR/port-webcore.log"

ICU_DATA="$(find "$SYSROOT/share/icu" -type f -name 'icudt*.dat' -print -quit 2>/dev/null || true)"
[ -n "$ICU_DATA" ] || { echo 'error: ICU data archive missing' >&2; exit 3; }
[ -f "$SYSROOT/lib/libxml2.a" ] || { echo 'error: libxml2 wasm archive missing' >&2; exit 4; }
[ -f "$SYSROOT/include/libxml2/libxml/parser.h" ] || { echo 'error: libxml2 wasm headers missing' >&2; exit 5; }
[ -f "$SYSROOT/lib/libsqlite3.a" ] || { echo 'error: SQLite wasm archive missing' >&2; exit 6; }
[ -f "$SYSROOT/include/sqlite3.h" ] || { echo 'error: SQLite wasm header missing' >&2; exit 7; }

rm -rf "$BUILD"
mkdir -p "$BUILD"

echo '==> configure pinned WebKit PORT=Emscripten for RiftWebCore Gate 2'
set +e
emcmake cmake -S "$WEBKIT" -B "$BUILD" -GNinja \
  -DPORT=Emscripten \
  -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_JIT=OFF \
  -DENABLE_C_LOOP=ON \
  -DENABLE_STATIC_JSC=ON \
  -DENABLE_REMOTE_INSPECTOR=OFF \
  -DENABLE_WEBASSEMBLY=OFF \
  -DUSE_SYSTEM_MALLOC=ON \
  -DICU_ROOT="$SYSROOT" \
  -DSQLite3_ROOT="$SYSROOT" \
  -DCMAKE_PREFIX_PATH="$SYSROOT" \
  -DCMAKE_FIND_ROOT_PATH="$SYSROOT" \
  -DJSC_EMBED_ICU_DATA_FILE="$ICU_DATA" \
  > "$LOGDIR/configure.log" 2>&1
configure_rc=$?
set -e

if [ "$configure_rc" -ne 0 ]; then
  echo 'RIFT_WEBCORE_CONFIGURE=failed'
  grep -nE 'CMake Error|Could NOT find|NOTFOUND|error:' "$LOGDIR/configure.log" | tail -n 120 || true
  tail -n 180 "$LOGDIR/configure.log" || true
  exit "$configure_rc"
fi

echo 'RIFT_WEBCORE_CONFIGURE=ready'

# Gate 2 deliberately builds WebCore, not WebKit UI/process layers. -k exposes
# a batch of real wasm port failures per CI iteration instead of one at a time.
set +e
ninja -C "$BUILD" -k 30 -j"${RIFT_JOBS:-2}" WebCore > "$LOGDIR/build.log" 2>&1
build_rc=$?
set -e

if [ "$build_rc" -ne 0 ]; then
  echo 'RIFT_WEBCORE_BUILD=failed'
  grep -nE 'FAILED:|error:|undefined symbol|CMake Error|ninja: build stopped' "$LOGDIR/build.log" | tail -n 160 || true
  tail -n 180 "$LOGDIR/build.log" || true
  exit "$build_rc"
fi

echo 'RIFT_WEBCORE_BUILD=ready'
find "$BUILD" -type f \( -name 'libWebCore.a' -o -name 'WebCore*.a' \) -print -exec ls -lh {} \; | tee "$LOGDIR/artifacts.txt"

echo 'RiftWebCore Gate 2 core target compiled. Next gate: link the Hello RiftBrowser document embedder.'
