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
