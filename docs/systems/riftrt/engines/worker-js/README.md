# RiftRT Worker-JS Reference Engine

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Status

Worker-JS is a **retained inactive engine design**.

Implementation remains inside `src/riftrt.js`, but current Android Gradle does not package `riftrt.js`, and no Kotlin caller activates a Worker-JS runtime.

## Source ownership

Retained only:
- `src/riftrt.js` `launchWorker(...)` branch.

There is no current Android-side Worker engine class/service/activity.

## Current reachability

No live path exists from:
- MainActivity;
- RiftNativeShell;
- RiftHeadlessJsRuntime;
- RiftBrowserAppHost;
- Android manifest;
- Gradle asset sync

to the retained Worker-JS branch.

Therefore it must not be counted as an APK capability.

## Retained design boundary

The old implementation may still be useful as design/reference code.

Any future reactivation must separately audit:
- worker source provenance;
- message/RPC schema;
- canvas/surface ownership;
- capability bridge;
- termination/lifecycle;
- resource limits;
- crash behavior.

Those retained implementation details are not current runtime promises.

## Critical invariants

- Worker-JS remains labeled inactive until current packaging + caller exists;
- source presence never equals activation;
- future Worker activation may not inherit raw shell/filesystem/native authority;
- no docs call it a fallback/default current engine.

## Failure signatures

- Gradle starts packaging riftrt.js or another Worker engine without audit -> activation change;
- Kotlin gains Worker runtime caller but this doc still says inactive -> documentation drift;
- docs advertise Worker-JS to installed apps today -> capability overclaim.

## Fix map

Retained design -> `src/riftrt.js`.

Future live engine -> requires a new explicit owner and child audit.

## Validation

Second audit proves:
- retained `launchWorker` exists;
- no current Gradle packaging of riftrt.js;
- zero current Kotlin Worker-JS activation path;
- no manifest component.

VERIFIED status for this README means its **inactive classification** is verified, not that the retained engine has passed device execution tests.
