# RiftOS JavaScript Source

## Verification status

**VERIFIED AGAINST CURRENT GRADLE/SOURCE — 2026-09-19.**

This directory is **not** the active Android shell source tree.

## Packaged live APK assets

`android/app/build.gradle.kts::syncRiftOsWebAssets` currently packages only:

- `riftpp-core.js`
- `riftvm.js`
- `semnexis-bootstrap.js`

They are copied into the generated `www` asset namespace and executed only by `RiftHeadlessJsRuntime` inside bounded QuickJS. The Semnexis asset is a bootstrap compiler host, not a browser shell or the eventual Semnexis native runtime.

## Retained reference/test source

All other JavaScript files in this directory are retained for one or more of:
- Node regression tests;
- migration/reference behavior;
- historical contracts;
- future porting work.

They must not be described as live Android APK authority unless Gradle and a current Kotlin caller prove that status.

Examples currently **not packaged as the OS shell**:
- `riftandroid-entry.js`
- `riftandroid-preload.js`
- `riftandroid-platform.js`
- `riftcore.js`
- `riftos.js`
- `riftapps.js`
- `riftrt.js`
- `riftgit.js`
- `riftworkspace-*.js`
- `riftdevlab.js`
- `riftlocal-platform.js`
- `riftshell-batch.js`
- `riftruntime.js`

## Ownership map

Repository ownership remains documented in `../docs/SOURCE_OWNERSHIP.md`. Ownership means “this source has a maintained documentation owner,” not “this source is currently packaged into the APK.”

For live engine ownership, use `../docs/systems/engine/README.md`.

## Rule

Before changing a retained JS module, determine whether the task is:
1. changing a live packaged headless asset;
2. changing a regression/reference oracle;
3. intentionally promoting a retained module back into the live APK.

Case 3 requires an explicit architecture change, Gradle packaging update, owner documentation update and build/runtime validation. Do not infer live status from the presence of a file in `src/`.
