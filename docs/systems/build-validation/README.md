# Build and Validation System

## Purpose

The build/validation layer catches source-contract regressions before the separate Android builder spends time on Gradle/package/signing work.

## Source ownership

- root `package.json` — `npm run check` / `check:transport` command chain.
- `scripts/validate-rift-wiring.mjs` — parses every active JS/MJS file and validates module imports, HTML/CSS assets, Android Activity registration/reachability, browser asset injection, relay entrypoints, package-script references, native caller/handler agreement and known removed runtime islands.
- `scripts/validate-rift-transport.mjs` — architectural/source invariants.
- `scripts/validate-rift-docs.mjs` — required system READMEs and active-source ownership coverage.
- `scripts/test-rift-ai-adapters.mjs` — AI selector registry tests.
- `scripts/test-rift-raw-protocol.mjs` — raw call parser/nested arguments/result protocol tests.
- `scripts/test-rift-shell-batch.mjs` — batch parser/preflight/rollback behavior.
- `scripts/test-rift-shell-git.mjs` — shell/Git workflow tests.
- `scripts/test-rift-app-import.mjs` — `.rift` package import contract.
- `android/app/build.gradle.kts` — Android source verification and web-asset sync.
- external `Arctic403/Riftos-builder` — manual Android build/sign/verify worker.

## Local/source check flow

`npm run check` starts with the wiring validator, which syntax-checks every active JS/MJS file and verifies the runtime reference graph, then runs the transport/documentation validators and focused test scripts. These are fast source-level checks and do not replace an Android compile.

Gradle `verifyRiftOsAndroidSources` rejects incomplete native snapshots. `syncRiftOsWebAssets` copies root `index.html`, styles, `src/**` and `workspace-live/**` into generated Android assets before build.

## External builder boundary

The RiftOS source repository intentionally has no automatic Actions build. The separate public builder is manually dispatched against an exact source commit, then compiles/alines/signs/verifies the APK according to project policy.

## Signing inputs and policy

`android/riftos-debug.keystore.b64` is the bundled legacy fallback signing identity used by the separate builder when private release secrets are not configured. It is a build input, not an application secret. The project intentionally keeps the current signing behavior for now. A private production signing-key migration is planned, but do **not** rotate/remove the fallback, require private signing secrets, or change this policy until the project owner explicitly requests it.

## Critical invariants

- Validators should check real architectural contracts, not obsolete removed behavior.
- A passing text/source validator must never be described as a successful Android build.
- Web assets required at runtime must be included by Gradle asset sync.
- Required native source verification should evolve when mandatory system classes change.
- Build triggering remains manual unless the project owner explicitly changes policy.

## Failure signatures

- `node --check` failure -> syntax error in named JS file.
- validator assertion failure after intentional architecture change -> update code and test together only when the old invariant is truly obsolete.
- Gradle source verification fails -> required native file missing from source snapshot.
- APK runs stale web code -> asset sync/source commit/build artifact mismatch.
- builder fails after source checks pass -> inspect Gradle/Android/package/signing stage separately.

## Fix map

Source invariant/tests -> `scripts/`.
Which web files enter APK -> Gradle asset sync.
Native dependency/SDK configuration -> Gradle.
Remote compile/sign packaging -> external builder.

## Validation discipline

For large changes use: audit -> snapshot -> targeted read -> patch -> local source checks -> Android build -> runtime verification -> commit. Update the owning subsystem README and its relevant validator in the same patch when changing an architectural invariant.
