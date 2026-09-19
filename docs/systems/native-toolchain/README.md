# Native Clang Bootstrap

## Status

**IMPLEMENTED HOST CONTRACT / TOOLCHAIN PAYLOAD PENDING — 2026-09-19**

The RiftOS source now has a bounded Android-native compiler authority for the Semnexis C++ bootstrap. The trusted compiler/sysroot payload is not yet committed or packaged, so `riftclang doctor` must report not-ready until that payload is added by the Builder.

## Purpose

The first Semnexis compiler is C++17. RiftOS needs the smallest practical native bootstrap path without turning RiftShell into a POSIX shell.

The selected bootstrap is direct Clang/LLD invocation:

```text
RiftShell
   |
   +-- riftclang doctor
   |
   +-- riftclang semnexis-build
             |
             v
     RiftNativeToolchain
             |
        fixed argv only
             |
             v
  APK-owned Clang driver + LLD
             |
      NDK-compatible sysroot
             |
             v
 documents/builds/Semnexis/semx-android.elf
```

CMake, Make, GCC, shell expansion, pipes and arbitrary compiler flags are not part of this bootstrap surface.

## Android W^X boundary

RiftOS targets modern Android. Executable toolchain code must not be downloaded or copied into writable RiftFS/app-home storage and then executed.

Executable pieces are therefore expected to be installed as trusted APK native payload:
- `libriftclang.so` — Clang driver built to run on the device ABI;
- `libriftlld.so` — LLD driver built to run on the device ABI.

The names use Android native-library packaging so the package manager owns the executable mapping/location.

Non-executable compiler data may live under:
- `<filesDir>/rift-toolchains/rift-clang-v1/sysroot`
- `<filesDir>/rift-toolchains/rift-clang-v1/resource`

Those trees contain headers, startup/link objects, libraries and Clang resource headers. They are data inputs, never executed.

Generated Semnexis ELF output is written under RiftFS Documents/Builds and is explicitly not executed from writable app-home storage.

## Supported target selection

Current contract:
- first preference: arm64-v8a -> `aarch64-linux-android26`;
- compatibility: armeabi-v7a -> `armv7a-linux-androideabi26`;
- API floor is 26, matching RiftOS minSdk.

No x86/x86_64 on-device host path is claimed yet.

## Fixed Semnexis build

`riftclang semnexis-build` accepts no extra arguments.

It compiles exactly:
- `workspace/Semnexis/src/frontend.cpp`
- `workspace/Semnexis/src/graph.cpp`
- `workspace/Semnexis/src/main.cpp`

Include root:
- `workspace/Semnexis/include`

Output:
- `documents/builds/Semnexis/semx-android.elf`

The command line is constructed as an argument list and passed directly to ProcessBuilder. No `/system/bin/sh`, command interpolation, redirection or environment expansion is used. Because the APK-packaged executable is named `libriftclang.so`, the host passes `--driver-mode=g++` explicitly instead of relying on `argv[0]` to select C++ driver behavior.

## Confinement

RiftNativeToolchain enforces:
- compiler executable canonical path equals the APK native-library Clang path;
- working directory equals canonical `workspace/Semnexis`;
- all source/include paths remain beneath that project;
- build output remains beneath RiftFS Documents/Builds/Semnexis;
- fixed source list;
- fixed compiler/linker flags;
- bounded compiler output;
- compiler deadline shorter than the enclosing RiftShell watchdog;
- no execution of the generated writable artifact.

## Current payload gate

The host code intentionally does not pretend a compiler exists.

`riftclang doctor` checks:
- packaged Clang executable;
- packaged LLD executable;
- sysroot tree;
- Clang resource directory;
- Semnexis source tree;
- selected Android target.

Until the trusted payload is packaged, `ready=false` is the correct state.

## Next payload work

The Builder needs a reproducible toolchain-payload job that:
1. builds the smallest Android-hosted Clang driver and LLD required by this contract;
2. strips unneeded LLVM tools/features;
3. stages the minimum NDK-compatible sysroot/resource tree needed by the Semnexis bootstrap;
4. records version and SHA-256 manifests;
5. packages executable drivers as APK native payload;
6. packages non-executable toolchain data for trusted first-run staging;
7. validates both arm64-v8a and the requested armeabi-v7a compatibility path independently.

Do not add a generic shell executor as a shortcut.

## Source ownership

Maintained live owners:
- `android/app/src/main/java/com/riftos/app/RiftNativeToolchain.kt` — bounded Android-native Clang/LLD bootstrap authority, target selection, confinement checks, fixed Semnexis build graph and compiler-process deadline;
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — fixed `riftclang doctor|semnexis-build` routing with no arbitrary compiler argument passthrough;
- `android/app/build.gradle.kts` — mandatory Android source inventory that requires `RiftNativeToolchain.kt` to remain part of the packaged native source set;
- `docs/systems/native-toolchain/README.md` — subsystem contract, payload boundary and promotion requirements;
- `docs/SOURCE_OWNERSHIP.md` — cross-system ownership index.

Builder-side payload proof is owned separately by the public Builder repository:
- `.github/workflows/riftclang-payload.yml`;
- `scripts/build-riftclang-payload.sh`;
- `scripts/verify-riftclang-payload.sh`.

The Semnexis source tree under `workspace/Semnexis` is an input project, not RiftOS subsystem source.

## Failure signatures

Treat the subsystem as failed or not promoted if any of these occur:
- `riftclang` is absent from the installed native command catalog after a build that claims this source revision;
- `riftclang doctor` reports `ready=true` while Clang, LLD, the sysroot, the resource directory or the Semnexis source root is missing;
- caller-provided text reaches a raw shell or arbitrary compiler/linker argument surface;
- the compiler executable resolves outside the APK-owned native-library directory;
- a source/include path escapes `workspace/Semnexis`;
- a build artifact escapes `documents/builds/Semnexis`;
- generated writable RiftFS code is executed directly;
- the compiler subprocess can outlive RiftShell's outer watchdog;
- ARM64/ARM32 target selection emits a host triple that disagrees with the selected ABI;
- the Builder publishes a payload without manifest/hash verification;
- source/docs ownership drift causes `validate-rift-docs.mjs` or the Gradle source snapshot to fail.

## Fix map

- bounded compiler authority / target selection / process lifetime -> `RiftNativeToolchain.kt`;
- native shell routing / command catalog -> `RiftNativeShell.kt`;
- Android mandatory source inventory -> `android/app/build.gradle.kts`;
- subsystem contract / W^X rules / payload policy -> this README;
- cross-system documentation ownership -> `docs/SOURCE_OWNERSHIP.md`;
- source documentation validator -> `scripts/validate-rift-docs.mjs`;
- Builder payload construction -> `Riftos-builder/.github/workflows/riftclang-payload.yml` + `Riftos-builder/scripts/build-riftclang-payload.sh`;
- Builder payload verification -> `Riftos-builder/scripts/verify-riftclang-payload.sh`;
- Semnexis bootstrap sources -> separate `workspace/Semnexis` project.

## Validation

Promotion requires all of the following:
- `npm run check` passes, including documentation parity and source wiring validation;
- `verifyRiftOsAndroidSources` includes and accepts `RiftNativeToolchain.kt`;
- the final APK contains the native toolchain host class when the source contract declares it;
- full RiftOS audit/architecture/security scans show no new native-toolchain finding;
- Builder payload scripts pass shell syntax and their dedicated payload verifier;
- the payload manifest and SHA-256 identities are checked before any payload is consumed by the main RiftOS build;
- installed-device `native` output lists `riftclang`;
- installed-device `riftclang doctor` reports the expected target and only reports `ready=true` when all trusted payload inputs are present;
- installed-device `riftclang semnexis-build` produces a non-empty ELF artifact at the fixed bounded output path;
- the generated artifact is inspected for the expected Android machine/ABI before calling the Semnexis bootstrap native-verified;
- no test or documentation marker is allowed to substitute for installed-device proof.
