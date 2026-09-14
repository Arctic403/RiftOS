# RiftBuild

## Purpose

RiftBuild is RiftOS's local build controller. The MVP provides deterministic project detection, build planning, target selection, run/artifact history, cache integration and a strict build doctor. It does not claim local APK compilation unless the native host explicitly advertises a trusted `localBuildExecutor` capability.

## Source ownership

- `src/riftbuild.js` owns doctor/plan/build/run/artifact/cache orchestration.
- `src/riftmemory-control.js` owns warm-cache controls.
- `src/riftrepo.js` owns checkpoint identity used by future reproducible builds.
- the existing external Builder remains the compile/sign gate until a native local executor is implemented and proven.

## Capability contract

`rift build doctor` must fail closed. Missing Java/Gradle/SDK/NDK/signing execution is a blocker, not a warning that can be bypassed. `rift build plan` remains useful offline and reports project shape, source count, working-set size, target and artifact destination without mutating source.

## Failure signatures

- `doctor blocked local execution` -> expected when this APK has no trusted local build executor.
- missing Gradle markers -> planning can continue but Android APK capability is not implied.
- low storage warning -> free local storage is below the conservative floor.

## Fix map

Doctor/planner/run records -> `src/riftbuild.js`.
Build cache -> `src/riftmemory-control.js`.
Compiled native executor -> future Android-native build subsystem; do not fake it in JavaScript.

## Validation

Verify doctor is honest, plan is read-only, invalid targets reject, build refuses before mutation when executor capability is absent, run records are durable when execution exists, and artifacts stay under `/documents/builds`.