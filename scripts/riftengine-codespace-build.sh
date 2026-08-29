#!/usr/bin/env bash
set -euo pipefail

ROOT="${RIFTENGINE_ROOT:-/workspaces/.riftengine/WebkitWasm}"
cd "$ROOT"

export JOBS="${JOBS:-2}"
export BIB_PTHREAD="${BIB_PTHREAD:-0}"
export BIB_JOBS="${BIB_JOBS:-2}"

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

trap 'rc=$?; if [ "$rc" -ne 0 ]; then log_tail; fi; exit "$rc"' EXIT

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
