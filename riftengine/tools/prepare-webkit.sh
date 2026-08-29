#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/riftengine/config/pins.env"
WORK="${RIFTENGINE_WORK:-$ROOT/.riftengine-work}"
WEBKIT="$WORK/WebKit"

mkdir -p "$WORK"

if [ ! -d "$WEBKIT/.git" ]; then
  echo "==> clone WebKit metadata"
  git clone --filter=blob:none --no-checkout --branch "$RIFT_WEBKIT_BRANCH" "$RIFT_WEBKIT_REPO" "$WEBKIT"
fi

echo "==> fetch pinned WebKit $RIFT_WEBKIT_COMMIT"
git -C "$WEBKIT" fetch --filter=blob:none origin "$RIFT_WEBKIT_COMMIT"
git -C "$WEBKIT" checkout --detach -f "$RIFT_WEBKIT_COMMIT"
git -C "$WEBKIT" clean -ffd

actual="$(git -C "$WEBKIT" rev-parse HEAD)"
if [ "$actual" != "$RIFT_WEBKIT_COMMIT" ]; then
  echo "error: WebKit pin mismatch: $actual" >&2
  exit 2
fi

python3 "$ROOT/riftengine/tools/port-jsc-emscripten.py" "$WEBKIT"

echo "==> WebKit prepared at $WEBKIT"
echo "    commit: $actual"
