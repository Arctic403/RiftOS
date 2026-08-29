#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${RIFTENGINE_WORK:-/tmp/riftengine-webcore}"
SYSROOT="$WORK/sysroot"
SRC_ROOT="$WORK/sqlite-src"
BUILD="$WORK/build-sqlite"
CACHE="$WORK/downloads"
LOGDIR="$ROOT/riftengine/logs/webcore"

# Keep this dependency scoped to WebCore Gate 2. pins.env is shared with the
# already-proven JSC workflow, so changing it would unnecessarily rebuild JSC.
SQLITE_VERSION="3.53.4"
SQLITE_ARCHIVE="sqlite-amalgamation-3530400.zip"
SQLITE_URL="https://www.sqlite.org/2026/$SQLITE_ARCHIVE"
SQLITE_SHA3_256="628a44cfe82c66aed1ccbbe85a562d2e33ebe64b3288981ed76285612227934e"
ARCHIVE="$CACHE/$SQLITE_ARCHIVE"

mkdir -p "$SYSROOT/include" "$SYSROOT/lib" "$CACHE" "$LOGDIR"
command -v emcc >/dev/null 2>&1 || { echo 'error: Emscripten environment is not active' >&2; exit 2; }
command -v emar >/dev/null 2>&1 || { echo 'error: emar is not available' >&2; exit 3; }

if [ -f "$SYSROOT/lib/libsqlite3.a" ] && [ -f "$SYSROOT/include/sqlite3.h" ]; then
  echo "RIFT_SQLITE_WASM=ready (cached $SQLITE_VERSION)"
  exit 0
fi

if [ ! -f "$ARCHIVE" ]; then
  echo "==> download SQLite $SQLITE_VERSION amalgamation"
  curl --fail --location --retry 3 --output "$ARCHIVE" "$SQLITE_URL"
fi

python3 - "$ARCHIVE" "$SQLITE_SHA3_256" <<'PY'
from pathlib import Path
import hashlib
import sys

path = Path(sys.argv[1])
expected = sys.argv[2].lower()
h = hashlib.sha3_256()
with path.open('rb') as f:
    for chunk in iter(lambda: f.read(1024 * 1024), b''):
        h.update(chunk)
actual = h.hexdigest()
if actual != expected:
    raise SystemExit(f"error: SQLite archive SHA3-256 mismatch: {actual}")
print(f"RIFT_SQLITE_ARCHIVE_SHA3={actual}")
PY

rm -rf "$SRC_ROOT" "$BUILD"
mkdir -p "$SRC_ROOT" "$BUILD"
python3 -m zipfile -e "$ARCHIVE" "$SRC_ROOT"

SQLITE_C="$(find "$SRC_ROOT" -type f -name sqlite3.c -print -quit)"
SQLITE_H="$(find "$SRC_ROOT" -type f -name sqlite3.h -print -quit)"
SQLITE_EXT_H="$(find "$SRC_ROOT" -type f -name sqlite3ext.h -print -quit)"
[ -n "$SQLITE_C" ] && [ -n "$SQLITE_H" ] || { echo 'error: SQLite amalgamation sources missing' >&2; exit 4; }

echo "==> compile SQLite $SQLITE_VERSION for wasm32"
emcc -O2 -DNDEBUG \
  -DSQLITE_THREADSAFE=0 \
  -DSQLITE_OMIT_LOAD_EXTENSION=1 \
  -DSQLITE_DEFAULT_MEMSTATUS=0 \
  -c "$SQLITE_C" -o "$BUILD/sqlite3.o" \
  > "$LOGDIR/sqlite-build.log" 2>&1

emar rcs "$SYSROOT/lib/libsqlite3.a" "$BUILD/sqlite3.o"
cp "$SQLITE_H" "$SYSROOT/include/sqlite3.h"
if [ -n "$SQLITE_EXT_H" ]; then
  cp "$SQLITE_EXT_H" "$SYSROOT/include/sqlite3ext.h"
fi

[ -f "$SYSROOT/lib/libsqlite3.a" ] || { echo 'error: SQLite wasm archive missing after build' >&2; exit 5; }
[ -f "$SYSROOT/include/sqlite3.h" ] || { echo 'error: SQLite wasm header missing after build' >&2; exit 6; }

echo "RIFT_SQLITE_WASM=ready ($SQLITE_VERSION)"
