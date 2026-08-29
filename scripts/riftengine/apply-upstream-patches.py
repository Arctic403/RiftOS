#!/usr/bin/env python3
"""Apply RiftEngine's reproducible compatibility layer to WebkitWasm.

This file intentionally keeps RiftOS-specific build fixes out of ad-hoc shell
snippets. Patches are idempotent and fail loudly if the pinned upstream tree
changes underneath us.
"""
from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()


def patch_once(path: str, marker: str, replacement: str, sentinel: str) -> None:
    p = ROOT / path
    s = p.read_text()
    if sentinel in s:
        print(f"[riftengine] already patched: {path} :: {sentinel}")
        return
    if marker not in s:
        raise SystemExit(f"[riftengine] upstream drift: marker missing in {path}: {marker!r}")
    p.write_text(s.replace(marker, replacement, 1))
    print(f"[riftengine] patched: {path}")


# FreeType is configured with Brotli/WOFF2 support before upstream's curl-tier
# normally builds Brotli. Build the same pinned Brotli release early and let the
# later tier reuse the resulting wasm sysroot archives.
webcore_marker = 'echo "=== freetype (no harfbuzz first pass) ==="'
webcore_block = r'''echo "=== brotli prebuild for freetype WOFF2 ==="
if [ ! -f "$SYSROOT/lib/libbrotlidec.a" ] || [ ! -f "$SYSROOT/lib/libbrotlicommon.a" ]; then
  fetch https://github.com/google/brotli/archive/refs/tags/v1.1.0.tar.gz brotli.tar.gz
  unpack brotli.tar.gz brotli
  cmake_build brotli brotli-build -DBROTLI_DISABLE_TESTS=ON
fi

'''
patch_once(
    "tools/build-deps/webcore-deps.sh",
    webcore_marker,
    webcore_block + webcore_marker,
    "brotli prebuild for freetype WOFF2",
)

# freedesktop.org returns HTTP 418 from some GitHub-hosted environments. Debian
# mirrors the exact fontconfig 2.15.0 source tarball, so this changes transport,
# not source version.
curl_tier = ROOT / "tools/build-deps/curl-tier.sh"
s = curl_tier.read_text()
old_url = "https://www.freedesktop.org/software/fontconfig/release/fontconfig-2.15.0.tar.xz"
new_url = "https://deb.debian.org/debian/pool/main/f/fontconfig/fontconfig_2.15.0.orig.tar.xz"
if old_url in s:
    s = s.replace(old_url, new_url)
elif new_url not in s:
    raise SystemExit("[riftengine] upstream drift: pinned fontconfig URL not found")

# fontconfig's utility link pulls static FreeType but pkg-config does not carry
# FreeType's private Brotli closure into that ordinary link command. Make the
# closure explicit so libbrotlidec.a resolves symbols from libbrotlicommon.a.
needle = (
    '     LIBXML2_CFLAGS="-I$SYSROOT/include/libxml2" \\\n'
    '     LIBXML2_LIBS="-L$SYSROOT/lib -lxml2 -licuuc -licudata -lz" \\\n'
    '     PKG_CONFIG_LIBDIR="$SYSROOT/lib/pkgconfig" \\\n'
)
replacement = (
    '     LIBXML2_CFLAGS="-I$SYSROOT/include/libxml2" \\\n'
    '     LIBXML2_LIBS="-L$SYSROOT/lib -lxml2 -licuuc -licudata -lz" \\\n'
    '     FREETYPE_CFLAGS="-I$SYSROOT/include/freetype2 -I$SYSROOT/include" \\\n'
    '     FREETYPE_LIBS="-L$SYSROOT/lib -lfreetype -lpng16 -lz -lbrotlidec -lbrotlicommon" \\\n'
    '     PKG_CONFIG_LIBDIR="$SYSROOT/lib/pkgconfig" \\\n'
)
sentinel = 'FREETYPE_LIBS="-L$SYSROOT/lib -lfreetype -lpng16 -lz -lbrotlidec -lbrotlicommon"'
if sentinel not in s:
    if needle not in s:
        raise SystemExit("[riftengine] upstream drift: fontconfig dependency block not found")
    s = s.replace(needle, replacement, 1)

curl_tier.write_text(s)
print("[riftengine] patched: tools/build-deps/curl-tier.sh")

print("[riftengine] compatibility layer applied successfully")
