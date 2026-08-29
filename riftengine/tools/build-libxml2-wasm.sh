#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/riftengine/config/pins.env"

WORK="${RIFTENGINE_WORK:-/tmp/riftengine-webcore}"
SYSROOT="$WORK/sysroot"
SRC_ROOT="$WORK/libxml2-src"
BUILD="$WORK/build-libxml2"
CACHE="$WORK/downloads"
ARCHIVE="$CACHE/$RIFT_LIBXML2_ARCHIVE"
LOGDIR="$ROOT/riftengine/logs/webcore"

mkdir -p "$SYSROOT" "$CACHE" "$LOGDIR"
command -v emcmake >/dev/null 2>&1 || { echo 'error: Emscripten environment is not active' >&2; exit 2; }

# A previous successful install is enough for repeated CI/debug invocations.
if [ -f "$SYSROOT/lib/libxml2.a" ] && [ -f "$SYSROOT/include/libxml2/libxml/parser.h" ]; then
  echo "RIFT_LIBXML2_WASM=ready (cached $RIFT_LIBXML2_VERSION)"
  exit 0
fi

if [ ! -f "$ARCHIVE" ]; then
  echo "==> download libxml2 $RIFT_LIBXML2_VERSION"
  curl --fail --location --retry 3 --output "$ARCHIVE" "$RIFT_LIBXML2_URL"
fi

rm -rf "$SRC_ROOT" "$BUILD"
mkdir -p "$SRC_ROOT" "$BUILD"
tar -xJf "$ARCHIVE" --strip-components=1 -C "$SRC_ROOT"

echo "==> configure libxml2 $RIFT_LIBXML2_VERSION for wasm32"
emcmake cmake -S "$SRC_ROOT" -B "$BUILD" -GNinja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$SYSROOT" \
  -DBUILD_SHARED_LIBS=OFF \
  -DLIBXML2_WITH_PYTHON=OFF \
  -DLIBXML2_WITH_TESTS=OFF \
  -DLIBXML2_WITH_PROGRAMS=OFF \
  -DLIBXML2_WITH_ICONV=OFF \
  -DLIBXML2_WITH_LZMA=OFF \
  -DLIBXML2_WITH_ZLIB=OFF \
  -DLIBXML2_WITH_HTTP=OFF \
  > "$LOGDIR/libxml2-configure.log" 2>&1

ninja -C "$BUILD" -j"${RIFT_JOBS:-2}" > "$LOGDIR/libxml2-build.log" 2>&1
cmake --install "$BUILD" > "$LOGDIR/libxml2-install.log" 2>&1

[ -f "$SYSROOT/lib/libxml2.a" ] || { echo 'error: libxml2 wasm archive missing after install' >&2; exit 3; }
[ -f "$SYSROOT/include/libxml2/libxml/parser.h" ] || { echo 'error: libxml2 wasm headers missing after install' >&2; exit 4; }

echo "RIFT_LIBXML2_WASM=ready ($RIFT_LIBXML2_VERSION)"
