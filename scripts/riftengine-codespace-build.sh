#!/usr/bin/env bash
set -euo pipefail

RIFTOS_ROOT="${RIFTOS_ROOT:-/workspaces/RiftOS}"
ROOT="${RIFTENGINE_ROOT:-/workspaces/.riftengine/WebkitWasm}"
LOG_DIR="${RIFTENGINE_LOG_DIR:-/workspaces/.riftengine}"
LOG_FILE="$LOG_DIR/riftengine-build.log"
source "$RIFTOS_ROOT/scripts/riftengine/version.env"

mkdir -p "$LOG_DIR"
cd "$ROOT"

export JOBS="${JOBS:-$RIFTENGINE_JOBS}"
export BIB_PTHREAD="${BIB_PTHREAD:-$RIFTENGINE_BIB_PTHREAD}"
export BIB_JOBS="${BIB_JOBS:-$RIFTENGINE_JOBS}"

exec > >(tee -a "$LOG_FILE") 2>&1

echo
echo "=== RiftEngine build started: $(date -Is) ==="
echo "profile=$RIFTENGINE_PROFILE pthread=$BIB_PTHREAD jobs=$BIB_JOBS"
echo "root=$ROOT"
echo "log=$LOG_FILE"

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

actual_commit="$(git rev-parse HEAD)"
if [ "$actual_commit" != "$RIFTENGINE_UPSTREAM_COMMIT" ]; then
  echo "Pinned upstream mismatch."
  echo "expected=$RIFTENGINE_UPSTREAM_COMMIT"
  echo "actual=$actual_commit"
  echo "Run: bash scripts/riftengine-codespace-setup.sh"
  exit 65
fi

# Re-apply idempotently in case the checkout was reset. This is the ONLY place
# RiftOS mutates the pinned upstream source before bootstrap/build.
python3 "$RIFTOS_ROOT/scripts/riftengine/apply-upstream-patches.py" "$ROOT"

# Recover from the two failures already observed without deleting successful
# dependency work. The source archive retry and fontconfig reconfigure are tiny
# compared with throwing away the full wasm sysroot.
FONTCONFIG_TARBALL="third_party/build-deps/fontconfig.tar.xz"
if [ -f "$FONTCONFIG_TARBALL" ] && ! tar -tf "$FONTCONFIG_TARBALL" >/dev/null 2>&1; then
  echo 'Removing incomplete fontconfig tarball.'
  rm -f "$FONTCONFIG_TARBALL"
fi
if [ ! -f "third_party/wasm-sysroot/lib/pkgconfig/fontconfig.pc" ] || [ ! -f "third_party/wasm-sysroot/etc/fonts/fonts.conf" ]; then
  rm -f third_party/build-deps/fontconfig/config.status third_party/build-deps/fontconfig/config.cache 2>/dev/null || true
  rm -rf third_party/build-deps/fontconfig-dest 2>/dev/null || true
fi

echo '=== Bootstrap pinned WebKit/Emscripten/dependencies ==='
bash tools/bootstrap.sh

if [ -f package-lock.json ]; then
  npm ci
fi

echo '=== Build RiftEngine WebCore/WASM ==='
BIB_PTHREAD="$BIB_PTHREAD" BIB_JOBS="$BIB_JOBS" bash tools/build-webcore.sh

echo '=== Verify engine contract ==='
test -s build/webcore/bin/embedder.js
test -s build/webcore/bin/embedder.wasm
test -s build/webcore/bin/bib-build-config.js

# The non-pthread iPhone profile must stamp itself as such. This catches stale
# outputs from a previous pthread build before we try them on Safari.
if grep -Eq 'BIB_PTHREAD_BUILD[[:space:]]*=[[:space:]]*true' build/webcore/bin/bib-build-config.js; then
  echo 'ERROR: output is a pthread build; iPhone profile requires BIB_PTHREAD_BUILD=false.'
  exit 66
fi

ls -lh build/webcore/bin/
echo
printf 'SUCCESS: RiftEngine compiled at %s/build/webcore/bin\n' "$ROOT"
echo "Full transcript: $LOG_FILE"
