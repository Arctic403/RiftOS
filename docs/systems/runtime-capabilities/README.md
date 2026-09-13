# Runtime Capability Report

## Purpose

`src/riftruntime.js` exposes a small, stable description of the active RiftOS delivery/runtime environment so features can detect supported platform capabilities without guessing from browser globals.

## Source ownership

`src/riftruntime.js`.

Public helpers:
- `deliveryMode()` -> current distribution mode (`android-apk`).
- `runtimeCapabilities()` -> structured capability flags/details.
- `info()` -> asynchronous runtime summary exposed to callers.

## Why this boundary exists

RiftOS has had browser/PWA/native experiments. Feature code should not scatter historical environment detection rules across the codebase. One capability report states what the current build supports.

## Critical invariants

- Report actual implemented capabilities, not roadmap goals.
- Capability flags should be stable names; adding a flag is safer than silently changing an existing flag's meaning.
- This module reports capabilities; it does not grant permissions.
- Do not expose secrets, device identifiers or privileged native objects through runtime info.

## Failure signatures

- Feature hidden even though implementation exists -> stale capability flag.
- Feature shown on unsupported path -> overly broad capability claim.
- Android-only code checks user agent instead of capability report -> ownership drift.

## Fix map

Update this file when the shipped runtime/delivery capability genuinely changes. Put implementation in the owning subsystem, then update the report after the feature exists.

## Validation

Compare reported flags with actual Android build and public runtime APIs after major platform changes. Keep the module syntax/simple enough to load early without side effects.
