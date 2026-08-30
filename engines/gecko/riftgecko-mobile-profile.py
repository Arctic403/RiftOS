#!/usr/bin/env python3
"""Apply the reproducible RiftGecko Mobile v1 profile to firefox-wasm v0.0.1.

This intentionally keeps Gecko's web-compatibility surface mostly intact and
changes the pieces that directly hurt iPhone WebKit: initial shared memory,
thread-pool pressure, stack reservation, release name metadata, and the
GPU-only manual JSPI post-link patch that Safari does not implement.

The script is fail-closed: every expected upstream string must be present
exactly once, so a future upstream change cannot silently produce an unknown
engine configuration.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

UPSTREAM_COMMIT = "eadcb5fa828d7e8b54ae2df13b6b3208f6553944"
PROFILE = {
    "id": "riftgecko-mobile-v1",
    "upstream": "HeyPuter/firefox-wasm",
    "upstreamCommit": UPSTREAM_COMMIT,
    "initialMemoryBytes": 268_435_456,
    "maximumMemoryBytes": 1_073_741_824,
    "stackBytes": 16_777_216,
    "pthreadPoolSize": 4,
    "pthreadPoolStrict": False,
    "manualGpuJSPI": False,
    "wasmOpt": "--all -Os",
    "profilingFunctionNames": False,
}


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match for {old!r}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: riftgecko-mobile-profile.py <firefox-wasm checkout>")

    root = Path(sys.argv[1]).resolve()
    build = root / "gecko.js" / "build-lib.sh"
    patcher = root / "gecko.js" / "patch-gecko-shaderfix.mjs"
    if not build.is_file() or not patcher.is_file():
        raise SystemExit(f"not a supported firefox-wasm checkout: {root}")

    # Directly reduce Safari's up-front shared-WASM footprint. Keep memory growth
    # enabled, but prevent the engine from reserving the upstream 512 MiB baseline.
    replace_once(
        build,
        "-sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=536870912 -sMAXIMUM_MEMORY=4294967296",
        "-sALLOW_MEMORY_GROWTH=1 -sINITIAL_MEMORY=268435456 -sMAXIMUM_MEMORY=1073741824",
    )
    replace_once(build, "-sSTACK_SIZE=67108864 -sEXIT_RUNTIME=0", "-sSTACK_SIZE=16777216 -sEXIT_RUNTIME=0")
    replace_once(
        build,
        "-pthread -sPTHREAD_POOL_SIZE=20 -sPTHREAD_POOL_SIZE_STRICT=0",
        "-pthread -sPTHREAD_POOL_SIZE=4 -sPTHREAD_POOL_SIZE_STRICT=0",
    )

    # Function-name profiling is useful during engine development but adds release
    # payload we do not need in the mobile artifact.
    replace_once(build, '  "$LINK_OPT" --profiling-funcs\n', '  "$LINK_OPT"\n')

    # Optimize the final linked module for size while staying on Binaryen's more
    # conservative -Os path. This is deliberate for the first Safari target:
    # current iOS 26 WebKit reports demonstrate a large threaded module becoming
    # viable after -Os, and -Os has fewer large-module optimizer edge cases than
    # chasing the last few bytes with -Oz. Gecko itself remains upstream -O2.
    replace_once(
        build,
        'WASMOPT_FLAGS="${GECKO_WASMOPT_FLAGS:--all -O4 -O3}"',
        'WASMOPT_FLAGS="${GECKO_WASMOPT_FLAGS:--all -Os}"',
    )

    # Keep upstream shader/proxy fixes, but remove Patch 3 entirely. Patch 3 wraps
    # gl_present_yield and every pthread entry with JSPI solely for GPU presentation.
    # RiftBrowser's iPhone path is software mode and Safari does not expose the JSPI
    # constructors, so compiling those wrappers into worker blobs is both unnecessary
    # and fatal.
    text = patcher.read_text(encoding="utf-8")
    marker = "// --- Patch 3: scoped (manual) JSPI for the GPU present-yield ---"
    if text.count(marker) != 1:
        raise SystemExit(f"{patcher}: expected one Patch 3 marker")
    prefix = text.split(marker, 1)[0].rstrip()
    patcher.write_text(
        prefix
        + "\n\n// RiftGecko Mobile: do not inject GPU-only JSPI wrappers.\n"
        + "// Safari software mode uses the original synchronous pthread entry path.\n"
        + "writeFileSync(path, src);\n",
        encoding="utf-8",
    )

    metadata = root / "RIFTGECKO_MOBILE_PROFILE.json"
    metadata.write_text(json.dumps(PROFILE, indent=2) + "\n", encoding="utf-8")

    # Fail closed if any of the Safari-incompatible manual wrappers remain in the
    # source that will regenerate the bundled worker runtime.
    patched = patcher.read_text(encoding="utf-8")
    if "WebAssembly.Suspending" in patched or "WebAssembly.promising" in patched:
        raise SystemExit("manual JSPI wrappers still present after mobile profile")

    print(json.dumps(PROFILE, indent=2))


if __name__ == "__main__":
    main()
