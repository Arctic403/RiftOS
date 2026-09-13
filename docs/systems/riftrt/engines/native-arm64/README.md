# RiftRT native-arm64 Engine Direction

## Purpose

`native-arm64` is a reserved RiftRT engine direction for code that is explicitly trusted and packaged with RiftOS. It is **not** an active arbitrary downloaded native-code runtime.

## Source ownership

The reserved branch is represented by `launchNative`/runtime parsing in `src/riftrt.js`. Any future real native implementation would also require Android-side code and a separate security review before this README can describe it as active.

## Current behavior and boundary

Modern Android does not provide a safe general-purpose design for executing arbitrary downloaded ELF binaries from writable app storage. RiftOS therefore must not turn this engine name into a shortcut around Android application signing, process isolation or capability policy.

## Critical invariants

- Never execute arbitrary user-downloaded ELF simply because a package declares `native-arm64`.
- Native modules, if implemented later, must be build-time packaged or admitted through an explicit trusted plugin mechanism.
- Permissions and RiftOS app identity still require a brokered design.

## Failure signatures

A package expecting downloaded native execution should be rejected/unsupported; that is correct behavior, not a compatibility bug. If the runtime silently executes writable native payloads, treat it as an architecture/security regression.

## Fix map

Unsupported-package messaging belongs in RiftRT runtime parsing/launch handling. A future native runtime requires a separately designed Android/native subsystem rather than ad-hoc code in the package loader.

## Validation

Verify `native-arm64` cannot execute arbitrary writable/downloaded native payloads. Any future activation must add build, ABI, signing/trust, lifecycle, crash-isolation and capability tests before changing this status.