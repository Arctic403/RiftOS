# RiftBuild

## Purpose

RiftBuild is RiftOS's local build controller. The MVP provides deterministic project detection, build planning, target selection, run/artifact history, cache integration and a strict build doctor. It does not claim local APK compilation unless the native host explicitly advertises a trusted `localBuildExecutor` capability.

## Source ownership

- `src/riftbuild.js` owns doctor/plan/build/submit/run/artifact/cache orchestration and validates the finite `rope-build-job-v1` contract used by R.O.P.E.
- `src/riftmemory-control.js` owns warm-cache controls.
- `src/riftrepo.js` owns checkpoint identity used by future reproducible builds.
- the existing external Builder remains the compile/sign gate until a native local executor is implemented and proven.

## Capability contract

`rift build doctor` must fail closed. Missing Java/Gradle/SDK/NDK/signing execution is a blocker, not a warning that can be bypassed. The current Android host explicitly reports `localBuildExecutor:false`; the finite `build.execute` native route exists only as a fail-closed placeholder and throws if a caller bypasses the doctor. `rift build plan` remains useful offline and reports project shape, source count, working-set size, target and artifact destination without mutating source.

RiftRT may expose this controller to an installed app only through the declared/granted `build.local` capability. The guest API is finite: `doctor`, `plan`, `submit`, `runs`, and `artifacts`. `submit` accepts only `rope-build-job-v1`, requires `engine:"gradle"`, forces the project under `/workspace`, restricts target to the existing RiftBuild target set, and only accepts Gradle task paths ending in `assembleDebug`, `assembleRelease`, `bundleDebug`, or `bundleRelease`. Task strings are passed as structured arguments; no shell command text is accepted.

## Failure signatures

- `doctor blocked local execution` -> expected when this APK has no trusted local build executor.
- missing Gradle markers -> planning can continue but Android APK capability is not implied.
- low storage warning -> free local storage is below the conservative floor.

## Fix map

Doctor/planner/run records -> `src/riftbuild.js`.
Build cache -> `src/riftmemory-control.js`.
Compiled native executor -> future Android-native build subsystem; do not fake it in JavaScript.

## Validation

Verify doctor is honest, plan is read-only, invalid targets reject, malformed/foreign build jobs reject, unsafe Gradle task names reject, RiftRT requires `build.local`, build refuses before mutation when executor capability is absent, run records are durable when execution exists, and artifacts stay under `/documents/builds`.