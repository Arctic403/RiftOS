#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${RIFTENGINE_WORK:-$ROOT/.riftengine-work}"
BUILD="${RIFT_JSC_BUILD:-$WORK/build-jsc}"
HOST="$ROOT/riftengine/host/rift-jsc-host.cpp"
OUT="$ROOT/riftengine/host-dist"
LOGDIR="$ROOT/riftengine/logs"

mkdir -p "$OUT" "$LOGDIR"
command -v em++ >/dev/null 2>&1 || { echo 'error: Emscripten environment is not active' >&2; exit 2; }
[ -s "$HOST" ] || { echo 'error: RiftJSC host source missing' >&2; exit 3; }
[ -d "$BUILD" ] || { echo 'error: cached JSC build directory missing; build JSC core first' >&2; exit 4; }

# Do not guess WebKit's internal library graph. Capture the exact successful
# JSCOnly link command produced by Ninja, then derive the custom-host link from
# that known-good graph. This script currently emits the link manifest; the next
# gate enables the host link once the manifest is verified in CI.
LINK_LINE="$(ninja -C "$BUILD" -t commands jsc 2>/dev/null | tail -n 1 || true)"
if [ -z "$LINK_LINE" ]; then
  echo 'error: unable to capture the JSCOnly link command' >&2
  exit 5
fi

printf '%s\n' "$LINK_LINE" > "$LOGDIR/jsc-link-command.txt"
printf '%s\n' "RiftEngine custom host source: $HOST" > "$LOGDIR/rift-host-plan.txt"
printf '%s\n' "JSC build cache: $BUILD" >> "$LOGDIR/rift-host-plan.txt"
printf '%s\n' "Host ABI: rift_jsc_create rift_jsc_eval rift_jsc_destroy rift_jsc_alive" >> "$LOGDIR/rift-host-plan.txt"

echo 'RIFT_HOST_LINK_MANIFEST=ready'
echo 'Captured the exact upstream JSCOnly link graph; custom host linking is gated until this manifest is verified.'
