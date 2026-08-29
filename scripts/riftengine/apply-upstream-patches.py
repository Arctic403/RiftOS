#!/usr/bin/env python3
"""Apply RiftEngine's reproducible compatibility layer to WebkitWasm.

Patches are intentionally idempotent and fail loudly if the pinned upstream
revision changes shape. Keep RiftOS-specific build fixes here rather than in
multiple shell/workflow snippets.
"""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def replace_once(path: str, old: str, new: str, sentinel: str) -> None:
    p = ROOT / path
    text = p.read_text()
    if sentinel in text:
        print(f"[riftengine] already patched: {path}")
        return
    if old not in text:
        raise SystemExit(f"[riftengine] upstream drift: expected block missing in {path}")
    p.write_text(text.replace(old, new, 1))
    print(f"[riftengine] patched: {path}")


# 1) FreeType is configured with Brotli/WOFF2 support before upstream's curl
# tier normally builds Brotli. Build the same pinned release early and let the
# later stage reuse it.
marker = 'echo "=== freetype (no harfbuzz first pass) ==="'
block = '''echo "=== brotli prebuild for freetype WOFF2 ==="
if [ ! -f "$SYSROOT/lib/libbrotlidec.a" ] || [ ! -f "$SYSROOT/lib/libbrotlicommon.a" ]; then
  fetch https://github.com/google/brotli/archive/refs/tags/v1.1.0.tar.gz brotli.tar.gz
  unpack brotli.tar.gz brotli
  cmake_build brotli brotli-build -DBROTLI_DISABLE_TESTS=ON
fi

'''
replace_once(
    "tools/build-deps/webcore-deps.sh",
    marker,
    block + marker,
    "brotli prebuild for freetype WOFF2",
)

# 2) freedesktop.org returns HTTP 418 from some GitHub-hosted environments.
# Debian mirrors the exact fontconfig 2.15.0 release tarball.
curl_tier = ROOT / "tools/build-deps/curl-tier.sh"
text = curl_tier.read_text()
old_url = "https://www.freedesktop.org/software/fontconfig/release/fontconfig-2.15.0.tar.xz"
new_url = "https://deb.debian.org/debian/pool/main/f/fontconfig/fontconfig_2.15.0.orig.tar.xz"
if old_url in text:
    text = text.replace(old_url, new_url)
elif new_url not in text:
    raise SystemExit("[riftengine] upstream drift: fontconfig 2.15.0 URL not found")

# 3) fontconfig utilities link static FreeType. The ordinary link does not pull
# FreeType's private Brotli closure, so make it explicit.
old_block = '''     LIBXML2_CFLAGS="-I$SYSROOT/include/libxml2" \\
     LIBXML2_LIBS="-L$SYSROOT/lib -lxml2 -licuuc -licudata -lz" \\
     PKG_CONFIG_LIBDIR="$SYSROOT/lib/pkgconfig" \\
'''
new_block = '''     LIBXML2_CFLAGS="-I$SYSROOT/include/libxml2" \\
     LIBXML2_LIBS="-L$SYSROOT/lib -lxml2 -licuuc -licudata -lz" \\
     FREETYPE_CFLAGS="-I$SYSROOT/include/freetype2 -I$SYSROOT/include" \\
     FREETYPE_LIBS="-L$SYSROOT/lib -lfreetype -lpng16 -lz -lbrotlidec -lbrotlicommon" \\
     PKG_CONFIG_LIBDIR="$SYSROOT/lib/pkgconfig" \\
'''
sentinel = 'FREETYPE_LIBS="-L$SYSROOT/lib -lfreetype -lpng16 -lz -lbrotlidec -lbrotlicommon"'
if sentinel not in text:
    if old_block not in text:
        raise SystemExit("[riftengine] upstream drift: fontconfig dependency block not found")
    text = text.replace(old_block, new_block, 1)

curl_tier.write_text(text)
print("[riftengine] patched: tools/build-deps/curl-tier.sh")
print("[riftengine] compatibility layer applied successfully")
