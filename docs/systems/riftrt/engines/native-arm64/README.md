# RiftRT native-arm64 Reserved Engine Direction

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Status

`native-arm64` is a **reserved/roadmap engine name only**.

No current RiftOS Android source implements a native ARM64 executable engine for installed Rift packages.

## Source ownership

Retained reference:
- `src/riftrt.js` parser/launch branch.

Current Android:
- no Kotlin native-arm64 runtime;
- no manifest service/activity;
- no Gradle activation of riftrt.js;
- no package host route selecting native-arm64.

## Security boundary

RiftOS must not execute arbitrary downloaded ELF/shared-object payloads from writable app storage simply because a package requests `native-arm64`.

Any future native execution design requires explicit trust/admission, for example:
- build-time packaged native code;
- signed/trusted plugin mechanism;
- Android process/service isolation;
- fixed ABI;
- capability broker;
- crash/lifecycle handling;
- update/revocation design.

The retained engine name grants none of that today.

## Current expected behavior

A current package expecting legacy RiftRT native-arm64 execution is unsupported.

That is the correct safe behavior.

## Critical invariants

- no arbitrary writable/downloaded native payload execution;
- no shell/process shortcut used as a fake native engine;
- engine remains labeled roadmap until a separately audited implementation exists;
- retained riftrt.js does not constitute Android native runtime code.

## Failure signatures

- app package launches ELF from C:/Programs or D: -> severe architecture/security regression;
- native shell/process executor becomes app-facing native-arm64 backend -> capability collapse;
- docs label native-arm64 live without Kotlin/build implementation -> status regression.

## Fix map

Retained name/reference -> `src/riftrt.js`.

Future native engine -> new explicit Android/native subsystem, not an ad-hoc package-loader branch.

## Validation

Second audit proves:
- retained name/branch exists;
- no current Kotlin/Gradle/manifest activation;
- no installed-app host route executes native package payloads.

VERIFIED status here means the **unsupported/roadmap classification** is verified.
