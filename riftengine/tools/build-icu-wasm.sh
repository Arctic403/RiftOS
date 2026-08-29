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

# With a cross build plus --disable-tools ICU 77 can install the static wasm
# libraries and stubdata library without copying the archive-mode .dat package
# into the prefix. The release source already contains the canonical little-
# endian archive used to generate the same ICU data. wasm32/Emscripten is
# little-endian, so install that archive into ICU's versioned data directory
# when make install omitted it.
DATA="$(find "$SYSROOT/share/icu" -type f -name 'icudt*.dat' -print -quit 2>/dev/null || true)"
if [ -z "$DATA" ]; then
  SOURCE_DATA="$(find "$SRC/source/data/in" -maxdepth 1 -type f -name 'icudt*l.dat' -print -quit 2>/dev/null || true)"
  if [ -z "$SOURCE_DATA" ]; then
    echo "error: ICU source data archive missing" >&2
    exit 3
  fi

  ICU_DATA_DIR="$(find "$SYSROOT/share/icu" -mindepth 1 -maxdepth 1 -type d -print -quit 2>/dev/null || true)"
  if [ -z "$ICU_DATA_DIR" ]; then
    ICU_DATA_DIR="$SYSROOT/share/icu"
  fi
  mkdir -p "$ICU_DATA_DIR"
  DATA="$ICU_DATA_DIR/$(basename "$SOURCE_DATA")"
  cp -f "$SOURCE_DATA" "$DATA"
  echo "==> installed ICU archive data: $DATA"
fi

if [ ! -f "$SYSROOT/lib/libicuuc.a" ]; then
  echo "error: ICU wasm sysroot missing libicuuc.a" >&2
  exit 3
fi
if [ ! -f "$SYSROOT/lib/libicui18n.a" ]; then
  echo "error: ICU wasm sysroot missing libicui18n.a" >&2
  exit 3
fi
if [ ! -f "$DATA" ]; then
  echo "error: ICU wasm sysroot missing archive data" >&2
  exit 3
fi

printf 'RiftEngine ICU ready:\n  sysroot: %s\n  data: %s\n' "$SYSROOT" "$DATA"
