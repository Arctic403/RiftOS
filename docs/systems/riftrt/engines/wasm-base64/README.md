# RiftRT WASM-base64 Reference Engine

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Status

WASM-base64 is a **retained inactive engine design** inside the un-packaged legacy RiftRT manager.

## Source ownership

Retained only:
- `src/riftrt.js` WASM branch/launcher.

Current Android code has:
- no Kotlin WASM-base64 runtime owner;
- no manifest component;
- no Gradle packaging of `riftrt.js`;
- no live caller selecting the engine.

## Current reachability

The string/branch still appears in retained RiftRT parsing and launch dispatch.

Because the entire manager is inactive, that branch is not current APK capability evidence.

The packaged `riftvm.js` is unrelated to WebAssembly and must not be conflated with this engine.

## Future activation boundary

Any future WASM engine requires a new explicit owner and review of:
- module provenance;
- maximum memory/pages;
- import allowlist;
- filesystem/network/native capabilities;
- execution interruption/yield limits;
- lifecycle/crash handling;
- package trust.

Retained historical implementation is not a promise that those guarantees hold today.

## Critical invariants

- WASM-base64 remains inactive until a current packaged caller exists;
- no arbitrary native/system imports;
- no docs call it a fallback/default engine;
- packaged RiftVM does not imply WASM activation.

## Failure signatures

- current APK begins selecting wasm-base64 without this status changing -> documentation drift;
- retained riftrt.js is treated as packaged because riftvm.js is packaged -> source-boundary error;
- future WASM imports expose broad native authority -> architecture regression.

## Fix map

Retained design -> `src/riftrt.js`.

Future live WASM runtime -> new audited Android/headless owner.

## Validation

Second audit proves retained branch presence plus zero current Android/Gradle/manifest activation.

VERIFIED status here means the **inactive classification** is verified, not that retained WASM execution is device-tested.
