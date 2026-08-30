#!/usr/bin/env bash
set -euo pipefail

trap 'status=$?; echo "RiftWebKit wrapper failed at line ${LINENO}: ${BASH_COMMAND} (exit ${status})" >&2' ERR

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <WebkitWasm helper checkout>" >&2
  exit 2
fi

RIFTOS_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HELPER_ROOT="$(cd "$1" && pwd)"
TP="$HELPER_ROOT/third_party"
SYSROOT="$TP/wasm-sysroot"
BUILD="$HELPER_ROOT/build/riftwebkit-probe"
FSROOT="$HELPER_ROOT/build/riftwebkit-probe-fs"

if [ ! -f "$TP/emsdk/emsdk_env.sh" ] || [ ! -d "$TP/WebKit" ] || [ ! -d "$SYSROOT" ]; then
  echo "RiftWebKit prerequisites are missing; run the pinned helper bootstrap first." >&2
  exit 3
fi

# shellcheck disable=SC1091
source "$TP/emsdk/emsdk_env.sh" >/dev/null 2>&1

command -v emcc >/dev/null
command -v emcmake >/dev/null
command -v ninja >/dev/null
emcc --version | head -1

# ICU's archive data location is install-layout dependent. Discover the
# archive from the sysroot rather than assuming a fixed share/lib path.
ICU_DATA="$(find "$SYSROOT" -type f -name 'icudt*.dat' -print -quit)"
if [ -z "$ICU_DATA" ] || [ ! -s "$ICU_DATA" ]; then
  echo "RiftWebKit ICU archive was not found below $SYSROOT." >&2
  find "$SYSROOT" -maxdepth 5 -type f \( -name 'icudt*' -o -path '*/icu/*' \) -print >&2 || true
  exit 4
fi
echo "RiftWebKit ICU archive: $ICU_DATA"

# Minimal runtime font tree. fontconfig's installed conf.d entries can be
# absolute/install-root symlinks after DESTDIR staging, so do not require the
# symlinks themselves to resolve inside the host filesystem. Use their names
# to copy the real conf.avail files into a self-contained Emscripten FS tree.
rm -rf "$FSROOT"
mkdir -p "$FSROOT/etc-fonts/conf.d" "$FSROOT/fonts"
cp "$SYSROOT/etc/fonts/fonts.conf" "$FSROOT/etc-fonts/fonts.conf"

fontconfig_conf_avail=""
for candidate in \
  "$SYSROOT/share/fontconfig/conf.avail" \
  "$SYSROOT/etc/fonts/conf.avail"; do
  if [ -d "$candidate" ]; then
    fontconfig_conf_avail="$candidate"
    break
  fi
done
if [ -z "$fontconfig_conf_avail" ]; then
  echo "RiftWebKit fontconfig conf.avail directory was not found." >&2
  find "$SYSROOT" -maxdepth 5 -type d -name 'conf.avail' -print >&2 || true
  exit 5
fi

for entry in "$SYSROOT/etc/fonts/conf.d/"*.conf; do
  [ -L "$entry" ] || [ -f "$entry" ] || continue
  source_file="$fontconfig_conf_avail/$(basename "$entry")"
  if [ -f "$source_file" ]; then
    cp "$source_file" "$FSROOT/etc-fonts/conf.d/"
  else
    echo "Skipping unresolved fontconfig entry: $(basename "$entry")" >&2
  fi
done

# Some DESTDIR/fontconfig combinations can install conf.d without usable
# links. Falling back to the available configs is safer than an empty runtime
# config and keeps the proof build independent of host symlink resolution.
if ! find "$FSROOT/etc-fonts/conf.d" -type f -name '*.conf' -print -quit | grep -q .; then
  cp "$fontconfig_conf_avail/"*.conf "$FSROOT/etc-fonts/conf.d/"
fi

cp /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf "$FSROOT/fonts/DejaVuSans.ttf"
test -s "$FSROOT/etc-fonts/fonts.conf"
test -s "$FSROOT/fonts/DejaVuSans.ttf"
test -n "$(find "$FSROOT/etc-fonts/conf.d" -type f -name '*.conf' -print -quit)"
echo "RiftWebKit fontconfig configs staged: $(find "$FSROOT/etc-fonts/conf.d" -type f -name '*.conf' | wc -l)"

# The pinned helper's WebKit port normally requires FreeType's Brotli-backed
# WOFF2 support. Our survival bootstrap intentionally disables that feature to
# break the dependency-order cycle. For this local HTML paint proof, web-font
# decoding is nonessential, so expose HAVE_WOFF_SUPPORT=OFF instead of lying
# that the missing codec exists or aborting configuration.
OPTIONS_EMSCRIPTEN="$TP/WebKit/Source/cmake/OptionsEmscripten.cmake"
test -s "$OPTIONS_EMSCRIPTEN"
python3 - "$OPTIONS_EMSCRIPTEN" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding='utf-8')
old = '''    else ()
        message(FATAL_ERROR "Sysroot FreeType lacks brotli/WOFF2 — rerun tools/build-deps/webcore-deps.sh (freetype section)")
    endif ()'''
new = '''    else ()
        message(WARNING "RiftWebKit probe: sysroot FreeType lacks Brotli/WOFF2; continuing without downloadable WOFF/WOFF2 fonts")
        SET_AND_EXPOSE_TO_BUILD(HAVE_WOFF_SUPPORT OFF)
    endif ()'''
if text.count(old) != 1:
    raise SystemExit('Pinned OptionsEmscripten WOFF2 requirement changed unexpectedly')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
PY
grep -F 'continuing without downloadable WOFF/WOFF2 fonts' "$OPTIONS_EMSCRIPTEN" >/dev/null
echo "RiftWebKit probe WOFF2 requirement relaxed."

EMBEDDER_CMAKE="$RIFTOS_ROOT/engines/webkit-poc/riftwebkit-embedder.cmake"
test -s "$EMBEDDER_CMAKE"
test -s "$RIFTOS_ROOT/engines/webkit-poc/riftwebkit-probe.cpp"

rm -rf "$BUILD"
mkdir -p "$BUILD"

set +e
emcmake cmake -S "$TP/WebKit" -B "$BUILD" -GNinja \
  -DPORT=Emscripten \
  -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_JIT=OFF \
  -DENABLE_C_LOOP=ON \
  -DENABLE_STATIC_JSC=ON \
  -DUSE_SYSTEM_MALLOC=ON \
  -DICU_ROOT="$SYSROOT" \
  -DCMAKE_FIND_ROOT_PATH="$SYSROOT" \
  -DJSC_EMBED_ICU_DATA_FILE="$ICU_DATA" \
  -DEMSCRIPTEN_EMBEDDER_CMAKE="$EMBEDDER_CMAKE" \
  -DRIFTWEBKIT_ICU_DATA_FILE="$ICU_DATA" \
  -DRIFTWEBKIT_FONTCONFIG_ETC_DIR="$FSROOT/etc-fonts" \
  -DRIFTWEBKIT_FONTS_DIR="$FSROOT/fonts" \
  "-DCMAKE_C_FLAGS=-msimd128" \
  "-DCMAKE_CXX_FLAGS=-msimd128" \
  > "$HELPER_ROOT/build/riftwebkit-configure.log" 2>&1
configure_status=$?
set -e
if [ "$configure_status" -ne 0 ]; then
  echo "=== RiftWebKit configure failed ===" >&2
  tail -n 160 "$HELPER_ROOT/build/riftwebkit-configure.log" >&2 || true
  exit "$configure_status"
fi

echo "RIFTWEBKIT CONFIGURE: OK"

jobs="${RIFTWEBKIT_JOBS:-4}"
set +e
ninja -C "$BUILD" -j "$jobs" -k 30 WebCore RiftWebKitProbe \
  > "$HELPER_ROOT/build/riftwebkit-ninja.log" 2>&1
build_status=$?
set -e
if [ "$build_status" -ne 0 ]; then
  echo "=== RiftWebKit ninja failed ===" >&2
  grep -n 'error:' "$HELPER_ROOT/build/riftwebkit-ninja.log" | tail -n 80 >&2 || true
  tail -n 160 "$HELPER_ROOT/build/riftwebkit-ninja.log" >&2 || true
  exit "$build_status"
fi

echo "RIFTWEBKIT NINJA: OK"

JS_FILE="$(find "$BUILD" -type f -name 'riftwebkit.js' -print -quit)"
WASM_FILE="$(find "$BUILD" -type f -name 'riftwebkit.wasm' -print -quit)"
test -n "$JS_FILE" && test -s "$JS_FILE"
test -n "$WASM_FILE" && test -s "$WASM_FILE"

mkdir -p "$RIFTOS_ROOT/.riftwebkit-output"
cp "$JS_FILE" "$RIFTOS_ROOT/.riftwebkit-output/riftwebkit.js"
cp "$WASM_FILE" "$RIFTOS_ROOT/.riftwebkit-output/riftwebkit.wasm"

printf 'JS:   '; du -h "$RIFTOS_ROOT/.riftwebkit-output/riftwebkit.js" | cut -f1
printf 'WASM: '; du -h "$RIFTOS_ROOT/.riftwebkit-output/riftwebkit.wasm" | cut -f1
