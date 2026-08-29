#!/usr/bin/env bash
set -euo pipefail

ROOT="${RIFTENGINE_ROOT:-/workspaces/.riftengine/WebkitWasm}"

sudo apt-get update
sudo apt-get install -y \
  build-essential git curl cmake ninja-build make python3 pkg-config \
  tar xz-utils unzip clang ripgrep gperf autoconf automake libtool gettext

mkdir -p "$(dirname "$ROOT")"
if [ ! -d "$ROOT/.git" ]; then
  git clone --depth 1 --branch non-pthread https://github.com/theogbob/WebkitWasm.git "$ROOT"
else
  git -C "$ROOT" fetch origin non-pthread --depth 1
  git -C "$ROOT" checkout non-pthread
  git -C "$ROOT" reset --hard origin/non-pthread
fi

cd "$ROOT"
python3 - <<'PY'
from pathlib import Path
p=Path('tools/build-deps/webcore-deps.sh')
s=p.read_text()
marker='echo "=== freetype (no harfbuzz first pass) ==="'
block=r'''echo "=== brotli prebuild for freetype WOFF2 ==="
if [ ! -f "$SYSROOT/lib/libbrotlidec.a" ]; then
  fetch https://github.com/google/brotli/archive/refs/tags/v1.1.0.tar.gz brotli.tar.gz
  unpack brotli.tar.gz brotli
  cmake_build brotli brotli-build -DBROTLI_DISABLE_TESTS=ON
fi

'''
if 'brotli prebuild for freetype WOFF2' not in s:
    if marker not in s:
        raise SystemExit('Could not locate FreeType stage in upstream webcore-deps.sh')
    s=s.replace(marker,block+marker,1)
    p.write_text(s)
PY

echo
printf 'RiftEngine Codespace ready at %s\n' "$ROOT"
printf 'Next: bash scripts/riftengine-codespace-build.sh\n'
