# RiftBuild platform boundary

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-10-07.**

This verifies the documented separation of generic RiftOS platform capabilities from external RiftBuild provider semantics. It does not certify the pending post-RiftCLI-removal Builder APK or device install.


RiftBuild application semantics are external to RiftOS.

The device-proven hosted provider owns the build recipe:

`Compile → Preflight → Pack → Sign → Verify → install/launch proof`

RiftOS does **not** own that recipe. RiftOS supplies only reusable platform capabilities that an external provider may call.

## Frozen rule

The promoted boundary is frozen after device proof.

A new RiftOS capability may be added only when a real device proof exposes a missing **generic reusable platform boundary**. Do not add project-specific build commands, compiler-specific wrappers, prepared-tree generators, APK packers, APK signers, language recipes, or compatibility aliases merely to make one provider easier to implement.

The following embedded implementations are retired and must stay absent:

- `RiftBuildLocalExecutor.kt`
- `RiftBuildKotlinCompiler.kt`
- `RiftBuildNativeToolchain.kt`
- `RiftBuildNativeApp.kt`
- `RiftApkV2Signer.kt`

The old Android-host clang provisioning path is retired too. RiftOS must not ship or regenerate `android-clang-v1.zip`, `libclang_exec.so`, `libld_lld_exec.so`, or `libld_lld_shim.so`.

## Current source owners

### `RiftLocalBuildCapability.kt`

Owns the generic `build.local` capability used by RAPP providers.

It provides:

- managed compiler registry status;
- registered compiler execution;
- inline registered compiler execution for host effects;
- project confinement under `D:/Workspace`;
- source/classpath/output bounds;
- delegation to `RiftManagedJvmToolService` for isolated JVM/DEX compiler payloads;
- delegation to `RiftNativeBufferCompilerService` for bounded native-buffer compiler payloads;
- JVM class-to-DEX conversion through `RiftJvmDexService`.

It does **not** own:

- project build recipes;
- app manifest/materialization recipes;
- prepared APK trees;
- ZIP/APK packing;
- APK signing;
- PackageInstaller;
- language-specific compile shortcuts.

### `RiftJvmDexService.kt`

Owns the reusable Android/JVM DEX boundary.

It materializes the bounded Android/JVM toolchain assets, reports the compatibility status schema consumed by the external provider, and converts project-owned JVM class output to indexed DEX with D8.

The wire status schema remains `riftbuild-kotlin-toolchain-status/2` because the proven external provider already consumes it. The schema name is compatibility surface only; the service itself is generic and contains no Kotlin compiler implementation.

### `RiftBuildManagedToolchains.kt`

Owns the compiler-id registry.

Registered compilers execute through bounded reusable engines:

- `native-buffer-v1`;
- `dex-json-v1`.

Compiler payloads may be project-owned exact-hash payloads or validated bundled seeds. RiftOS does not interpret language semantics above the generic compiler request/response protocol.

### `RiftManagedJvmToolService.kt`

Owns exact-hash isolated JVM/DEX compiler execution.

The compiler implementation runs outside the main RiftOS classloader boundary and returns bounded JSON results. This service is execution infrastructure, not a build recipe.

### `RiftNativeBufferCompilerService.kt`

Owns the bounded native-buffer execution engine.

It remains because it is a reusable runtime/compiler primitive used by generic managed compilers and RAPP execution. It is **not** the retired Android-host clang toolchain.

### `RiftBuildPlatformTools.kt`

Owns the narrow RiftShell-facing platform surface.

Surviving commands are:

- `riftbuild compiler-status <project>`
- `riftbuild compiler-run <project> <compiler-id> <request.json>`
- `riftbuild jvm-status`
- `riftbuild jvm-dex <project> <classes-dir> <output-dir> [minSdk]`
- `riftbuild pack-rapp <project>`
- `riftbuild install-rapp <artifact.rapp>`
- `riftbuild launch-rapp <id>`
- `riftbuild rapp-list`
- `riftbuild verify <signed-apk>`
- `riftbuild install-proof <signed-apk>`
- `riftbuild install-status`
- `riftbuild launch-proof`

There is no embedded `kotlin-compile`, native clang compile/object extraction, native-app preparation, APK `pack`, or APK `sign` command.

Persistent RiftShell jobs are used for surviving long operations such as `compiler-run`, `jvm-dex`, RAPP package/install work, verification, and install proof. The job lane remains bounded submit/status/result/cancel/list infrastructure and does not reintroduce RiftCLI.

### `RiftApkV2Verifier.kt`

Owns verification-only APK Signature Scheme v2 parsing.

It verifies:

- APK Signing Block structure;
- v2 signer block ID;
- SHA256withRSA signer signature;
- certificate/public-key binding;
- protected APK content digest.

It has no Android Keystore dependency, no key generation, and no signing method.

Verification remains inside RiftOS because Android installation is a platform responsibility and RiftOS must independently verify the signed artifact before PackageInstaller.

### `RiftBuildInstaller.kt`

Owns the generic user-confirmed Android PackageInstaller/launch boundary.

It accepts a verified APK plus `RiftApkV2Verifier.VerifyResult`, derives/validates package identity, persists install status, retains Android user-confirmation intents across foreground transitions, and launches only the package bound to the latest verified install record.

It does not sign APKs and does not contain project package allowlists.

### `RiftRappCapabilityBroker.kt`

Owns permission-gated generic host effects.

Relevant build-provider capabilities are:

- `build.local`;
- `signing.identity`;
- bounded filesystem read/write operations.

`signing.identity` exposes only generic public identity metadata plus SHA256withRSA PKCS#1 sign/verify operations. The private key remains in Android Keystore and never leaves RiftOS.

The external provider owns APK-v2 signing-block construction and calls this generic cryptographic capability when it needs a signature.

## External provider contract

The provider is a RAPP and is not bundled into the RiftOS source tree.

For a provider build:

1. **Compile** — provider selects its recipe and registered compiler, then uses `build.local` compiler execution.
2. **DEX when required** — provider uses generic JVM DEX conversion.
3. **Preflight** — provider validates project/app inputs and its own recipe invariants.
4. **Pack** — provider constructs the unsigned APK deterministically.
5. **Sign** — provider constructs APK-v2 signed data and asks `signing.identity` only for the generic RSA signature.
6. **Verify** — provider verifies its completed artifact; RiftOS independently verifies again before install.
7. **Install/launch proof** — RiftOS PackageInstaller handles the Android platform boundary with normal user confirmation.

Provider state and provider-specific manifests/tests stay in the external provider repository.

## RAPP boundary

The generic application ABI is `riftos-app-abi/1`.

`RiftRappManager` owns RAPP package/install/launch state under `/C:/Programs`. Installed program data is immutable. Mutable app state is stored separately as bounded atomic `state.bin` and reloaded as the effective program/state on the next launch.

`RiftRappHost` owns generic event/effect sequencing, bounded host-effect depth, lifecycle delivery, and generic view rendering. `FLOW_COLUMN` is scroll-contained so dynamic content growth cannot collapse text inputs.

`RiftRappAbsoluteView` and the generic frame renderer own UI/event primitives. The host must not contain build-provider-specific UI or Rift++ project-specific build semantics.

Current adapters include compatibility lanes for existing Rift++ protocols plus `riftpp-generic-v1` for forward generic Rift++ RAPP work. Adapter compatibility does not give those languages ownership of the host ABI.

## Managed Kotlin compiler seed

The managed Kotlin implementation remains a separate compiler payload APK built from `:rift-managed-kotlin-tool`.

RiftOS may ship a validated compiler seed and the Android/JVM toolchain assets required to run it. That does not restore `RiftBuildKotlinCompiler.kt`: the provider calls the generic registry and generic DEX service directly.

The main RiftOS application must not embed the desktop Kotlin compiler implementation.

## Shell and MCP boundary

`RiftNativeShell` routes `riftbuild` to `RiftBuildPlatformTools`.

`RiftToolHost.shouldSubmitShellJob` recognizes only surviving long generic operations. Retired build commands must not be recognized or silently aliased.

Browser apps do not receive the old `Rift.build.doctor/plan/prepare/submit/runs/artifacts` orchestration API. Build providers use the RAPP capability broker instead.

## Validation

Source validation must prove all of the following:

- promoted generic owners are mandatory Gradle sources;
- retired embedded build source files are absent;
- `build.local` does not gain pack/sign/install authority;
- JVM DEX stays project-confined and D8-owned;
- `RiftBuildPlatformTools` exposes only the surviving generic command set;
- `RiftApkV2Verifier` remains verification-only and keyless;
- the capability broker owns `build.local` and `signing.identity`;
- the browser orchestration API remains absent;
- the old clang payload/generator does not return.

Builder final-APK validation must additionally prove:

- generic compiler/JVM DEX/platform/verifier markers survived compilation;
- retired native compile/toolchain/prepared-app/runtime-profile markers are absent from DEX;
- old clang ZIP/JNI payloads are absent from the APK;
- generic managed compiler toolchain assets and native-buffer execution hosts remain present;
- RAPP ABI/capability/state markers survived compilation;
- Android package/minSdk/targetSdk/debuggable/signature/native payload checks remain green.

## Freeze decision

The full external provider path was device-proven through compile, preflight, pack, sign, verify, Android install, editor launch, typing, clear, preview, and downstream editor native compile/preflight/pack/sign output.

That proof promoted the external-provider architecture and authorized deletion of the embedded fallback.

From this point forward:

- provider semantics stay external;
- RiftOS capabilities stay generic;
- no duplicate fallback path is maintained;
- no legacy compiler/preparer/packer/signer is kept “just in case”;
- a boundary extension requires real proof that the missing primitive is reusable beyond one provider or language.

## Source ownership

- Generic compiler execution: `RiftLocalBuildCapability.kt`, `RiftBuildManagedToolchains.kt`, `RiftManagedJvmToolService.kt`, `RiftNativeBufferCompilerService.kt`.
- Generic DEX, signature verification, installation and shell access: `RiftJvmDexService.kt`, `RiftApkV2Verifier.kt`, `RiftBuildInstaller.kt`, `RiftBuildPlatformTools.kt`.
- Hosted effects and app lifecycle: `RiftRappCapabilityBroker.kt`, `RiftRappManager.kt`, `RiftRappHost.kt`.
- Provider-specific compiler choice, preflight, ZIP/APK packing and APK-v2 signing-block construction: external `workspace/RiftBuild-Hosted`, not this repository.
- APK build and final artifact gates: external RiftOS Builder; the user manually dispatches Builder builds.

## Failure signatures

- A missing external provider artifact is not permission to restore a hardwired compiler/packer path inside RiftOS.
- `RiftBuildLocalExecutor`, `RiftApkV2Signer`, or retired clang payload requirements in source or Builder gates indicate legacy architecture regression.
- A `build.local` implementation with direct ZIP/APK pack/sign/install semantics violates the generic boundary.
- An APK that fails signature verification must never be handed to PackageInstaller.
- A successful source smoke or Builder compilation alone is not installed-device app behavior proof.

## Fix map

1. Locate the owning source, external provider contract and specific failing regression before editing.
2. Correct generic host capability defects in RiftOS; correct compiler/preflight/pack/sign recipes in external Hosted.
3. Preserve `RiftApkV2Verifier` as independent, keyless verification and `RiftBuildInstaller` as user-confirmed platform install.
4. Run source ownership, wiring, transport, architecture and package verification gates; require real installed-device proof before promoting changes.
5. Do not trigger or alter the user's manual RiftOS Builder workflow.
