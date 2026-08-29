#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/riftengine/config/pins.env"
WORK="${RIFTENGINE_WORK:-$ROOT/.riftengine-work}"
WEBKIT="$WORK/WebKit"
SYSROOT="$WORK/sysroot"
BUILD="${RIFT_JSC_BUILD:-$WORK/build-jsc}"
HOST="$ROOT/riftengine/host/rift-jsc-host.cpp"
OUT="$ROOT/riftengine/host-dist"
DIST="$ROOT/riftengine/jsc-dist/custom"
LOGDIR="$ROOT/riftengine/logs"
OBJ="$OUT/rift-jsc-host.o"

mkdir -p "$OUT" "$DIST" "$LOGDIR"
command -v em++ >/dev/null 2>&1 || { echo 'error: Emscripten environment is not active' >&2; exit 2; }
[ -s "$HOST" ] || { echo 'error: RiftJSC host source missing' >&2; exit 3; }
[ -d "$BUILD" ] || { echo 'error: JSC build directory missing; build JSC core first' >&2; exit 4; }
[ -s "$BUILD/lib/libJavaScriptCore.a" ] || { echo 'error: libJavaScriptCore.a missing' >&2; exit 5; }
[ -s "$BUILD/lib/libWTF.a" ] || { echo 'error: libWTF.a missing' >&2; exit 6; }

ICU_DATA="$(find "$SYSROOT/share/icu" -type f -name 'icudt*.dat' -print -quit 2>/dev/null || true)"
[ -n "$ICU_DATA" ] || { echo 'error: ICU data archive missing' >&2; exit 7; }

# Preserve the exact known-good stock-shell link graph for diagnostics. The
# successful CI capture proved the wasm graph is JavaScriptCore + WTF + ICU.
LINK_LINE="$(ninja -C "$BUILD" -t commands jsc 2>/dev/null | tail -n 1 || true)"
[ -n "$LINK_LINE" ] || { echo 'error: unable to capture JSCOnly link command' >&2; exit 8; }
printf '%s\n' "$LINK_LINE" > "$LOGDIR/jsc-link-command.txt"

# JavaScriptCore's public C API headers include one another as
# <JavaScriptCore/...>. Make a tiny header view instead of depending on a
# platform framework/install layout.
API_VIEW="$WORK/rift-jsc-api"
rm -rf "$API_VIEW"
mkdir -p "$API_VIEW/JavaScriptCore"
find "$WEBKIT/Source/JavaScriptCore/API" -maxdepth 1 -type f -name '*.h' -exec cp {} "$API_VIEW/JavaScriptCore/" \;
[ -s "$API_VIEW/JavaScriptCore/JavaScript.h" ] || { echo 'error: JavaScriptCore public API headers missing' >&2; exit 9; }

# Compile only the RiftEngine-owned embedder. JSC/WTF themselves were already
# compiled by the upstream-pinned JSCOnly target above.
em++ \
  -std=c++20 -O3 -DNDEBUG -fno-exceptions -fno-rtti \
  -DSTATICALLY_LINKED_WITH_JavaScriptCore=1 \
  -I"$API_VIEW" \
  -c "$HOST" -o "$OBJ"

# Link the tiny host against the exact libraries proven by the stock JSC shell.
# MODULARIZE gives both Node CI and the browser worker an explicit factory,
# while the exported C ABI is intentionally much smaller than the jsc CLI.
em++ \
  -O3 -DNDEBUG -Wl,--gc-sections \
  "$OBJ" \
  "$BUILD/lib/libJavaScriptCore.a" \
  "$BUILD/lib/libWTF.a" \
  "$SYSROOT/lib/libicudata.a" \
  "$SYSROOT/lib/libicui18n.a" \
  "$SYSROOT/lib/libicuuc.a" \
  -sSTACK_SIZE=4MB \
  -sINITIAL_MEMORY=128MB \
  -sALLOW_MEMORY_GROWTH=1 \
  -sMAXIMUM_MEMORY=1GB \
  -sNO_EXIT_RUNTIME=1 \
  -sMODULARIZE=1 \
  -sEXPORT_NAME=createRiftJSC \
  -sEXPORTED_FUNCTIONS="['_main','_rift_jsc_create','_rift_jsc_eval','_rift_jsc_destroy','_rift_jsc_alive']" \
  -sEXPORTED_RUNTIME_METHODS="['cwrap']" \
  --embed-file "$ICU_DATA@$ICU_DATA" \
  -o "$OUT/rift-jsc.js"

[ -s "$OUT/rift-jsc.js" ] || { echo 'error: custom RiftJSC JS launcher missing' >&2; exit 10; }
[ -s "$OUT/rift-jsc.wasm" ] || { echo 'error: custom RiftJSC wasm missing' >&2; exit 11; }

# Prove the actual reason this host exists: two separate calls must evaluate in
# the exact same JSGlobalContextRef, while destroy/recreate must reset it.
cat > "$OUT/persistence-smoke.cjs" <<'NODE'
const createRiftJSC = require('./rift-jsc.js');

(async () => {
  const Module = await createRiftJSC();
  const create = Module.cwrap('rift_jsc_create', 'number', []);
  const evaluate = Module.cwrap('rift_jsc_eval', 'string', ['string']);
  const destroy = Module.cwrap('rift_jsc_destroy', null, []);
  const alive = Module.cwrap('rift_jsc_alive', 'number', []);

  if (!create() || !alive()) throw new Error('RiftJSC context did not initialize');

  const first = evaluate('globalThis.__riftPersistent = 41; __riftPersistent');
  const second = evaluate('++globalThis.__riftPersistent');
  if (first !== '41' || second !== '42') {
    throw new Error(`persistent context failed: first=${first} second=${second}`);
  }

  destroy();
  if (alive()) throw new Error('RiftJSC context survived destroy');
  if (!create()) throw new Error('RiftJSC context did not recreate');
  const reset = evaluate('typeof globalThis.__riftPersistent');
  if (reset !== 'undefined') throw new Error(`context reset failed: ${reset}`);

  console.log('RIFT_HOST_PERSISTENCE=42');
  console.log('RIFT_HOST_RESET=undefined');
  destroy();
})().catch((error) => {
  console.error(error && error.stack ? error.stack : error);
  process.exit(1);
});
NODE

(
  cd "$OUT"
  node persistence-smoke.cjs
) | tee "$LOGDIR/rift-host-smoke.log"
grep -F 'RIFT_HOST_PERSISTENCE=42' "$LOGDIR/rift-host-smoke.log" >/dev/null
grep -F 'RIFT_HOST_RESET=undefined' "$LOGDIR/rift-host-smoke.log" >/dev/null

cp "$OUT/rift-jsc.js" "$DIST/rift-jsc.js"
cp "$OUT/rift-jsc.wasm" "$DIST/rift-jsc.wasm"
cp "$OUT/persistence-smoke.cjs" "$DIST/persistence-smoke.cjs"

printf '%s\n' "RiftEngine custom host source: $HOST" > "$LOGDIR/rift-host-plan.txt"
printf '%s\n' "JSC core: $BUILD/lib/libJavaScriptCore.a" >> "$LOGDIR/rift-host-plan.txt"
printf '%s\n' "WTF core: $BUILD/lib/libWTF.a" >> "$LOGDIR/rift-host-plan.txt"
printf '%s\n' "Host ABI: rift_jsc_create rift_jsc_eval rift_jsc_destroy rift_jsc_alive" >> "$LOGDIR/rift-host-plan.txt"
printf '%s\n' "Persistence gate: PASS required before browser promotion" >> "$LOGDIR/rift-host-plan.txt"

ls -lh "$OUT/rift-jsc.js" "$OUT/rift-jsc.wasm"
echo 'RIFT_HOST_LINK=ready'
echo 'RiftEngine persistent JavaScriptCore host linked and persistence-smoke passed.'
