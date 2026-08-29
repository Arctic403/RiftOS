#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/riftengine/config/pins.env"
WORK="${RIFTENGINE_WORK:-$ROOT/.riftengine-work}"
DEPS="$WORK/deps"
SYSROOT="$WORK/sysroot"
ARCHIVE="$DEPS/$RIFT_ICU_ARCHIVE"
SRC="$DEPS/icu-src"
HOST="$DEPS/icu-host"
WASM="$DEPS/icu-wasm"

command -v emconfigure >/dev/null 2>&1 || { echo "error: Emscripten environment is not active" >&2; exit 2; }
mkdir -p "$DEPS" "$SYSROOT"

if [ ! -f "$ARCHIVE" ]; then
  echo "==> download ICU $RIFT_ICU_RELEASE"
  curl -fL --retry 4 --retry-delay 3 "$RIFT_ICU_URL" -o "$ARCHIVE"
fi

if [ ! -d "$SRC/source" ]; then
  rm -rf "$SRC"
  mkdir -p "$SRC"
  tar xzf "$ARCHIVE" -C "$SRC" --strip-components=1
fi

# ICU's unknown-platform fragment is a stub. Emscripten's Unix-like build
# environment works with the Linux make fragment for this static cross build.
cp -f "$SRC/source/config/mh-linux" "$SRC/source/config/mh-unknown"

if [ ! -x "$HOST/bin/pkgdata" ]; then
  echo "==> build native ICU tools"
  rm -rf "$HOST"
  mkdir -p "$HOST"
  (
    cd "$HOST"
    "$SRC/source/runConfigureICU" Linux --disable-tests --disable-samples
    make -j"$(nproc)"
  )
fi

if [ ! -f "$SYSROOT/lib/libicuuc.a" ] || [ ! -f "$SYSROOT/lib/libicui18n.a" ]; then
  echo "==> build single-thread wasm ICU"
  rm -rf "$WASM"
  mkdir -p "$WASM"
  (
    cd "$WASM"
    CFLAGS="-Oz" CXXFLAGS="-Oz" \
      emconfigure "$SRC/source/configure" \
        --host=wasm32-unknown-emscripten \
        --with-cross-build="$HOST" \
        --enable-static --disable-shared \
        --disable-tests --disable-samples --disable-extras --disable-tools \
        --disable-dyload \
        --with-data-packaging=archive \
        --prefix="$SYSROOT"
    emmake make -j"${RIFT_JOBS:-2}"
    emmake make install
  )
fi

DATA="$(find "$SYSROOT/share/icu" -type f -name 'icudt*.dat' -print -quit 2>/dev/null || true)"
if [ ! -f "$SYSROOT/lib/libicuuc.a" ] || [ ! -f "$SYSROOT/lib/libicui18n.a" ] || [ -z "$DATA" ]; then
  echo "error: ICU wasm sysroot verification failed" >&2
  exit 3
fi

printf 'RiftEngine ICU ready:\n  sysroot: %s\n  data: %s\n' "$SYSROOT" "$DATA"
