#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/riftengine/src/riftengine.cpp"
DIST="$ROOT/riftengine/dist"

command -v em++ >/dev/null 2>&1 || {
  echo "error: em++ not found; install/activate Emscripten first" >&2
  exit 1
}

mkdir -p "$DIST"
rm -f "$DIST/riftengine-core.js" "$DIST/riftengine-core.wasm"

em++ "$SRC" \
  -std=c++20 \
  -Oz \
  -flto \
  -s WASM=1 \
  -s MODULARIZE=1 \
  -s EXPORT_ES6=1 \
  -s ENVIRONMENT=web \
  -s FILESYSTEM=0 \
  -s ALLOW_MEMORY_GROWTH=1 \
  -s INITIAL_MEMORY=16777216 \
  -s MAXIMUM_MEMORY=268435456 \
  -s MALLOC=emmalloc \
  -s ASSERTIONS=0 \
  -s EXPORTED_FUNCTIONS='["_rift_engine_create","_rift_engine_resize","_rift_engine_tick","_rift_engine_pointer","_rift_engine_load_html","_rift_engine_pixels","_rift_engine_width","_rift_engine_height","_rift_engine_version","_malloc","_free"]' \
  -s EXPORTED_RUNTIME_METHODS='["HEAPU8","UTF8ToString","stringToUTF8","lengthBytesUTF8"]' \
  -o "$DIST/riftengine-core.js"

printf '\nRiftEngine prototype outputs:\n'
ls -lh "$DIST/riftengine-core.js" "$DIST/riftengine-core.wasm"
