#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${RIFTENGINE_WORK:-/tmp/riftengine-webcore}"
SYSROOT="$WORK/sysroot"
SRC_ROOT="$WORK/zlib-src"
BUILD="$WORK/build-zlib"
LOGDIR="$ROOT/riftengine/logs/webcore"

# Keep WebCore-only dependencies out of pins.env so dependency bring-up does
# not retrigger the already-proven RiftJSC build. v1.3.1 is the signed upstream
# zlib tag; pin the exact commit behind that tag for deterministic CI.
ZLIB_VERSION="1.3.1"
ZLIB_REPO="https://github.com/madler/zlib.git"
ZLIB_COMMIT="51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf"

mkdir -p "$SYSROOT/include" "$SYSROOT/lib" "$LOGDIR"
command -v emcmake >/dev/null 2>&1 || { echo 'error: Emscripten environment is not active' >&2; exit 2; }
command -v git >/dev/null 2>&1 || { echo 'error: git is required to fetch pinned zlib' >&2; exit 3; }

if [ -f "$SYSROOT/lib/libz.a" ] && [ -f "$SYSROOT/include/zlib.h" ] && [ -f "$SYSROOT/include/zconf.h" ]; then
  echo "RIFT_ZLIB_WASM=ready (cached $ZLIB_VERSION)"
  exit 0
fi

rm -rf "$SRC_ROOT" "$BUILD"
mkdir -p "$SRC_ROOT" "$BUILD"

echo "==> fetch zlib $ZLIB_VERSION @ $ZLIB_COMMIT"
git -C "$SRC_ROOT" init -q
git -C "$SRC_ROOT" remote add origin "$ZLIB_REPO"
git -C "$SRC_ROOT" fetch --quiet --depth 1 origin "$ZLIB_COMMIT"
git -C "$SRC_ROOT" checkout --quiet --detach FETCH_HEAD
actual_commit="$(git -C "$SRC_ROOT" rev-parse HEAD)"
[ "$actual_commit" = "$ZLIB_COMMIT" ] || { echo "error: zlib commit mismatch: $actual_commit" >&2; exit 4; }
echo "RIFT_ZLIB_SOURCE=$actual_commit"

echo "==> configure zlib $ZLIB_VERSION for wasm32"
emcmake cmake -S "$SRC_ROOT" -B "$BUILD" -GNinja \
  -DCMAKE_BUILD_TYPE=Release \
  -DZLIB_BUILD_EXAMPLES=OFF \
  > "$LOGDIR/zlib-configure.log" 2>&1

# zlib's upstream CMake file declares both shared and static targets. WebCore's
# wasm build needs only the static archive, so avoid building/installing the
# shared target and stage the three required files explicitly.
ninja -C "$BUILD" -j"${RIFT_JOBS:-2}" zlibstatic > "$LOGDIR/zlib-build.log" 2>&1

cp "$BUILD/libz.a" "$SYSROOT/lib/libz.a"
cp "$SRC_ROOT/zlib.h" "$SYSROOT/include/zlib.h"
cp "$BUILD/zconf.h" "$SYSROOT/include/zconf.h"

[ -f "$SYSROOT/lib/libz.a" ] || { echo 'error: zlib wasm archive missing after build' >&2; exit 5; }
[ -f "$SYSROOT/include/zlib.h" ] || { echo 'error: zlib wasm header missing after build' >&2; exit 6; }
[ -f "$SYSROOT/include/zconf.h" ] || { echo 'error: zconf wasm header missing after build' >&2; exit 7; }

echo "RIFT_ZLIB_WASM=ready ($ZLIB_VERSION)"
