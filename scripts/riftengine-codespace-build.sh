#!/usr/bin/env bash
set -euo pipefail

ROOT="${RIFTENGINE_ROOT:-/workspaces/.riftengine/WebkitWasm}"
LOG_DIR="${RIFTENGINE_LOG_DIR:-/workspaces/.riftengine}"
LOG_FILE="$LOG_DIR/riftengine-build.log"
mkdir -p "$LOG_DIR"
cd "$ROOT"

export JOBS="${JOBS:-2}"
export BIB_PTHREAD="${BIB_PTHREAD:-0}"
export BIB_JOBS="${BIB_JOBS:-2}"

# Keep a persistent transcript so mobile terminals cannot hide the real failure.
exec > >(tee -a "$LOG_FILE") 2>&1

echo
echo "=== RiftEngine build started: $(date -Is) ==="
echo "ROOT=$ROOT"
echo "LOG=$LOG_FILE"

log_tail() {
  echo
  echo '=== Recent dependency/build logs ==='
  find third_party/build-deps build -type f \( -name '*configure.log' -o -name '*build.log' -o -name '*make*.log' -o -name '*.log' \) -print 2>/dev/null \
    | sort | tail -n 35 | while read -r f; do
        echo
        echo "===== $f ====="
        tail -n 120 "$f" || true
      done
}

on_exit() {
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo
    echo "FAILED: RiftEngine build exited with code $rc"
    log_tail
    echo
    echo "Full transcript: $LOG_FILE"
  fi
  exit "$rc"
}
trap on_exit EXIT

# Apply small reproducible fixes to the upstream research checkout.
# 1) WebCore's FreeType stage requires Brotli before the upstream curl tier runs.
python3 - <<'PY'
from pathlib import Path
p = Path('tools/build-deps/webcore-deps.sh')
s = p.read_text()
marker = 'echo "=== freetype (no harfbuzz first pass) ==="'
block = r'''echo "=== brotli prebuild for freetype WOFF2 ==="
if [ ! -f "$SYSROOT/lib/libbrotlidec.a" ]; then
  fetch https://github.com/google/brotli/archive/refs/tags/v1.1.0.tar.gz brotli.tar.gz
  unpack brotli.tar.gz brotli
  cmake_build brotli brotli-build -DBROTLI_DISABLE_TESTS=ON
fi

'''
if 'brotli prebuild for freetype WOFF2' not in s:
    if marker not in s:
        raise SystemExit('Could not locate FreeType stage in upstream webcore-deps.sh')
    p.write_text(s.replace(marker, block + marker, 1))

# 2) freedesktop.org returns HTTP 418 from some GitHub-hosted environments.
# Use Debian's mirror of the exact fontconfig 2.15.0 source tarball instead.
p = Path('tools/build-deps/curl-tier.sh')
s = p.read_text()
old = 'https://www.freedesktop.org/software/fontconfig/release/fontconfig-2.15.0.tar.xz'
new = 'https://deb.debian.org/debian/pool/main/f/fontconfig/fontconfig_2.15.0.orig.tar.xz'
if old in s:
    p.write_text(s.replace(old, new))
elif new not in s:
    raise SystemExit('Could not locate the pinned fontconfig 2.15.0 download URL')
PY

# Remove a failed/partial prior download so fetch() will retry from the mirror.
FONTCONFIG_TARBALL="third_party/build-deps/fontconfig.tar.xz"
if [ -f "$FONTCONFIG_TARBALL" ] && ! tar -tf "$FONTCONFIG_TARBALL" >/dev/null 2>&1; then
  echo 'Removing incomplete fontconfig tarball from the previous HTTP 418 attempt.'
  rm -f "$FONTCONFIG_TARBALL"
fi

echo 'Applied RiftEngine dependency hotfixes (Brotli order + fontconfig mirror).'

echo '=== RiftEngine persistent bootstrap ==='
bash tools/bootstrap.sh

if [ -f package-lock.json ]; then
  npm ci
fi

echo '=== RiftEngine WebCore/WASM build ==='
BIB_PTHREAD="$BIB_PTHREAD" BIB_JOBS="$BIB_JOBS" bash tools/build-webcore.sh

echo '=== Verifying output ==='
test -s build/webcore/bin/embedder.js
test -s build/webcore/bin/embedder.wasm
test -s build/webcore/bin/bib-build-config.js
ls -lh build/webcore/bin/

echo
printf 'SUCCESS: RiftEngine compiled at %s/build/webcore/bin\n' "$ROOT"
echo "Full transcript: $LOG_FILE"
