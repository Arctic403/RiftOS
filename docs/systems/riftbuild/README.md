# RiftBuild — Native Local Build Controller

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

Source implementation and static audits are current. Android compilation and installed-device execution are still pending and are not implied by this source-verification marker.

## Purpose

RiftBuild is the bounded Android-native build controller for compiling and packaging RiftOS/Rift++ projects locally on the device.

It does **not** expose a general Linux shell, arbitrary `ProcessBuilder`, downloaded executable payloads, or unrestricted Gradle/NDK command execution.

The long-term goal is fully in-house Android builds:

```text
Rift++ source
 -> Rift IR
 -> ARMv7 + AArch64 native emission
 -> ELF/shared objects
 -> Android package layout
 -> APK signing
 -> artifact verification
 -> PackageInstaller
```

Gradle/NDK compatibility is an adapter above that core, not the authority boundary.

## Why RiftBuild cannot simply unpack desktop NDK tools

RiftOS targets modern Android. Executing downloaded binaries from writable app storage is not a safe or supported foundation, and the official Android NDK host packages are desktop-host toolchains rather than an Android-host build runtime.

Therefore RiftBuild v0.1 deliberately keeps execution in Android/Kotlin/Rift++ owned code and fails closed when a stage has no in-process implementation.

## Native owner

Source owners in this patch:

- `android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt` — bounded build/package command owner;
- `android/app/src/main/java/com/riftos/app/RiftApkV2Signer.kt` — bounded APK Signature Scheme v2 signer/verifier;
- `android/app/src/main/java/com/riftos/app/RiftBuildInstaller.kt` — user-confirmed PackageInstaller + launch-proof owner

Existing callers to activate without expanding the MCP catalog:

- `RiftNativeShell.kt` — fixed `riftbuild` command family;
- `RiftBrowserAppHost.kt` — existing `build.local` capability methods.

The retained `src/riftbuild.js` remains reference-only and is not repackaged or revived as authority.

## Source ownership

Maintained live owners:
- `android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt` — native build controller, direct-ELF bridge materializer, fixed binary-manifest V0 encoder, prepared APK packager and bounded sign/verify/install command routing;
- `android/app/src/main/java/com/riftos/app/RiftApkV2Signer.kt` — Android-Keystore RSA key owner plus narrow APK Signature Scheme v2 encoder/verifier;
- `android/app/src/main/java/com/riftos/app/RiftBuildInstaller.kt` — exact-package PackageInstaller session/result/first-launch proof owner;
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — fixed native `riftbuild` command routing;
- `android/app/src/main/java/com/riftos/app/RiftBrowserAppHost.kt` — capability-gated `build.local` app surface;
- `scripts/test-riftbuild-native.mjs` — focused authority/confinement/source contract;
- `scripts/test-rift-local-platform.mjs` — retained local-first boundary regression covering historical RiftBuild reference source.

Retained reference:
- `src/riftbuild.js` — non-authoritative historical/local-first design source.

Cross-system ownership remains indexed in `docs/SOURCE_OWNERSHIP.md`.

## Authority model

RiftBuild accepts only projects under RiftFS `workspace/` / `D:/Workspace`.

It may write only:
- its private run records;
- `D:/Builds` packaged artifacts;
- explicit derived build outputs under the selected project's `build/riftbuild/` subtree.

RiftBuild may not mutate project source, compiler source, evidence source or arbitrary workspace paths.

It does not:
- execute caller-provided commands;
- execute arbitrary host binaries;
- download toolchains;
- change MCP tool count;
- bypass `build.local` grants;
- push Git;
- enable the experimental CLI;
- install an APK without a separate explicit install path.

The Local Agent/MCP remains outside the compiler authority. Model-facing calls can request fixed RiftBuild operations only through existing bounded surfaces.

## v0.1 commands

Native shell:

```text
riftbuild doctor [project]
riftbuild validate <project>
riftbuild plan <project> [arm32|arm64|universal]
riftbuild prepare-riftpp-v0 <project> [arm32|arm64|universal]
riftbuild pack <project> [arm32|arm64|universal]
riftbuild runs [limit]
riftbuild artifacts [project]
```

Installed Rift app API keeps the existing capability boundary:

```text
build.doctor
build.plan
build.prepare
build.submit
build.runs
build.artifacts
```

No new MCP tool is required.

## v0.1 project inspection

The controller recognizes ordinary Android/Gradle markers but does not claim Gradle has executed.

For the current Rift++ native proof it can verify:
- `settings.gradle.kts`;
- root/app Gradle files;
- `app/src/main/AndroidManifest.xml`;
- NativeActivity declaration;
- `android.app.lib_name`;
- no `uses-permission` request in the proof manifest;
- ARMv7 and AArch64 generated sources;
- declared dual ABI filters.

The verifier records bounded source SHA-256 identities where useful.

## Rift++ direct-ELF bridge

For the first self-contained native proof, RiftBuild may consume only the SHA-bound Rift++ bridge artifact:

`compiler/native_backend/evidence/DIRECT-ELF-SHARED-V0-BYTES.json`

`riftbuild prepare-riftpp-v0` must:
- resolve the artifact inside the selected workspace project;
- reject unknown schema/version;
- reject byte values outside 0..255;
- enforce the declared byte count;
- verify raw SHA-256 before writing;
- verify ELF magic, class and target machine;
- for V0, treat the selected project as the Rift++ root and write only `apk-proof/build/riftbuild/prepared/lib/<abi>/libriftpp_nativeproof.so`;
- write atomically and re-hash the materialized file;
- never synthesize or substitute ELF bytes from Kotlin source.

This is a bootstrap bridge from proven Rift++ runtime output to the package layer. It is replaced later by direct compiler/backend artifact emission, but the byte-validation contract remains.

### Fixed binary AndroidManifest V0

The same prepare step owns a deliberately narrow AAPT-free binary XML encoder for the first `apk-proof` only.

It is valid only while these audited source identities remain unchanged:
- `apk-proof/app/src/main/AndroidManifest.xml` SHA-256 `eb0e8b7f3020499b50b135d1ef93c60af89f997c7c1984ec3d17d32c1595a6c1`;
- `apk-proof/app/build.gradle.kts` SHA-256 `1d1739a07896c4d7f1e521fa154a0285c5c4eefe87eab718830fee37194c0765`.

V0 emits only the current proof contract:
- package `com.riftpp.nativeproof`;
- versionCode `1` / versionName `0.1.0-native-proof`;
- minSdk `26` / targetSdk `36`;
- `application android:hasCode=false`;
- exported `android.app.NativeActivity`;
- `android.app.lib_name = riftpp_nativeproof`;
- MAIN/LAUNCHER intent filter;
- no permissions and no resource table dependency.

The encoder writes Android binary XML chunks directly: XML header, UTF-8 string pool, framework attribute resource map, namespace nodes, typed start/end element nodes. Any source-hash drift blocks preparation instead of silently producing a stale manifest.

The V0 layout is now fully canonicalized: every XML node uses `lineNumber=1`, comments use `NO_INDEX`, raw lexical attribute values remain in the string pool, and all chunks are little-endian/4-byte aligned. The independently reconstructed reference is exactly 1,440 bytes with SHA-256 `ac035bb5bf89f55a3f34bae8eea980108324d2f36333f1e708f8a0b82af8e7c2`. The Kotlin encoder must reproduce that identity exactly or preparation fails closed.

This V0 encoder is not a general XML/resource compiler.

## Deterministic prepared-artifact package stage

v0.1 owns a real APK ZIP packaging stage for **prepared Android artifacts**.

Prepared input lives under the project only:

```text
build/riftbuild/prepared/
  AndroidManifest.xml
  lib/
    arm64-v8a/*.so
    armeabi-v7a/*.so
  [resources.arsc]
  [assets/...]
```

Rules:
- `AndroidManifest.xml` must be Android binary XML, not plain source XML;
- only safe relative paths are accepted;
- duplicate entries are rejected;
- file count and total bytes are bounded;
- target ABI selection is explicit;
- output is written under `D:/Builds/<project>/<run-id>/`;
- the produced unsigned APK receives a SHA-256 receipt.

This stage proves local package construction. It does **not** imply compilation or signing.

## Signing / verification / install V0 contract

The bootstrap signer is deliberately narrower than a general Android signing tool.

Signing:
- input must be an unsigned APK already produced under `D:/Builds`;
- the signer uses one persistent RSA-2048 key owned by Android Keystore under the RiftOS app identity;
- V0 emits APK Signature Scheme v2 with RSASSA-PKCS1-v1_5 + SHA-256 (algorithm ID `0x0103`);
- no private-key bytes may leave Android Keystore;
- the signer must parse the ZIP EOCD/central-directory boundaries itself and reject ZIP64, malformed or already-signed input;
- content digests follow the AOSP v2 1 MiB chunk construction and the signing block is inserted immediately before the ZIP central directory;
- output remains under the same bounded `D:/Builds` run directory and receives a SHA-256 receipt.

Verification:
- verification is independent of the signing operation;
- it must parse the v2 signing block, verify the RSA signature over signed-data, match certificate/public-key identity, recompute the protected APK content digest, and reject structural drift;
- verification records the certificate SHA-256, public-key SHA-256, content digest and final APK SHA-256;
- a signed artifact is not installable-claimed until this verifier passes.

Install/launch proof:
- V0 installation is restricted to the exact bootstrap package `com.riftpp.nativeproof`;
- RiftOS declares `REQUEST_INSTALL_PACKAGES` and uses Android `PackageInstaller`, never raw package-manager shell commands;
- normal Android unknown-source trust/user confirmation remains mandatory;
- install status is persisted under RiftBuild system state;
- after successful installation, the proof launcher targets only the exported `android.app.NativeActivity` for `com.riftpp.nativeproof`;
- no arbitrary package name, arbitrary APK path or silent/background install authority is exposed.

The v2 implementation is intentionally a small bootstrap subset. General multi-signer/v3/v4/key-import support is out of scope for the RiftLLM+ return milestone.

## Stage graph

Current stage ownership:

1. source validation — RiftBuild native controller;
2. Rift++/IR validation — existing Rift++ proof surfaces;
3. native ELF/shared-object generation — Rift++ direct-ELF V0 reference Buffer emission PASS; runtime-byte bridge/materialization active;
4. APK layout/package — RiftBuild v0.1 prepared-artifact packer;
5. signing — bounded Android-Keystore APK Signature Scheme v2 owner defined by this V0 contract;
6. artifact verification — independent v2 signature/content-digest verifier defined by this V0 contract;
7. install — user-confirmed PackageInstaller + exact proof-package launch owner defined by this V0 contract.

A build/run record must say `blocked` rather than fake success when an upstream stage is unavailable.

## Gradle / NDK compatibility

Gradle remains useful as a project-description/build compatibility format.

RiftBuild may later consume Gradle/Android project metadata, but it must not create an unrestricted command runner.

The preferred native path is:
- parse the required Android project contract;
- call Rift++ native backend directly;
- use direct ELF/shared-object emission;
- package/sign using RiftBuild;
- use Gradle/NDK only as differential/reference compatibility where required.

If a future Android-hosted LLVM/LLD implementation is added, it must be embedded/in-process and separately audited.

## Run records

Run state is persisted under:

`/system/riftbuild/v1/runs`

Each record binds:
- run id;
- project;
- target;
- source validation;
- stage status;
- blockers;
- produced artifacts;
- SHA-256 identities;
- timestamps.

Records do not confer trust or patch approval.

## Failure signatures

The subsystem is invalid if:
- caller text reaches `ProcessBuilder`/raw `exec`;
- projects can escape RiftFS workspace;
- outputs can escape `D:/Builds`;
- `build.submit` reports success without all required stages;
- plain text `AndroidManifest.xml` is mislabeled as an installable packaged manifest;
- only one ABI is packaged for `universal`;
- signing/install is claimed before its native owner exists;
- RiftBuild silently enables the experimental CLI;
- MCP catalog expands just to expose build internals.

## Fix map

- local build controller/package stage -> `RiftBuildLocalExecutor.kt`;
- shell command routing -> `RiftNativeShell.kt`;
- installed-app capability surface -> `RiftBrowserAppHost.kt`;
- Android source snapshot -> `android/app/build.gradle.kts`;
- source ownership -> `docs/SOURCE_OWNERSHIP.md`;
- source/build validation -> `scripts/test-riftbuild-native.mjs` + build-validation docs;
- Rift++ direct ELF emission -> `workspace/rift++/compiler/native_backend` (separate project authority).

## Validation

Promotion requires:
- focused source test proves workspace/output confinement and absence of raw process execution;
- existing `build.local` remains capability-gated;
- `src/riftbuild.js` remains unpackaged reference code;
- exact Kotlin snapshot includes the native owner;
- source ownership and subsystem docs are synchronized;
- `npm run check` remains green;
- actual Android Builder compilation is still required before this new Kotlin source is called installed/live;
- a real on-device APK artifact must be inspected before claiming local APK generation PASS.
