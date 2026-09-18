# RiftRT v1/v2 Design Reference

## Status

**HISTORICAL / UNVERIFIED DESIGN REFERENCE.**

This document previously described the JavaScript `src/riftrt.js` multi-engine runtime as the active application runtime. Current Gradle does not package that module.

## Current source-proven runtime

- existing HTML/JS packages under C:/Programs -> `RiftBrowserAppHost.kt`;
- production Rift++ `.rxe` compile/inspect/run/exec -> `RiftHeadlessJsRuntime.kt` + packaged `riftpp-core.js` + `riftvm.js`;
- Worker-JS and wasm-base64 branches in `src/riftrt.js` -> retained/inactive until a live packaged caller is proven;
- native-arm64 -> reserved/not implemented as a current execution engine.

Package manifest examples and historical ABI notes in older revisions should be treated as design material, not proof of current APK behavior.

Use `docs/systems/riftrt/README.md` and per-engine source audits for current status.
