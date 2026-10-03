# RiftBuild — Native Local Build Controller

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-30.**

The core RiftBuild validation/planning and historical Rift++ V0 packaging path are installed-device proven. Current installed-device evidence also covers bounded APK v2 signing/verification, user-confirmed PackageInstaller success and exact launch requests for allowlisted proof/editor packages. A recorded launch request is not treated as proof that the launched target survived its own loader/runtime initialization. Current source additionally stages the Rift++ Android Native R1 Bionic-valid ELF repro described below.

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

## Native toolchain execution policy

RiftBuild may use local or downloaded native compiler toolchains when they are explicitly provisioned for Android-host execution. The official desktop NDK host packages are not assumed to run on Android unchanged, so toolchain provisioning remains a separate compatibility responsibility.

Native Compile V1 executes the configured compiler directly with a structured argument vector. Project/source text is never passed to a shell command, `/system/bin/sh -c` is not part of the build path, outputs remain confined to the selected project's `build/riftbuild/` subtree, and produced ELF shared objects are independently checked before packaging.

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
- `android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt` — native build controller, managed compiler command routing, generic package orchestration and bounded sign/verify/install command routing;
- `android/app/src/main/java/com/riftos/app/RiftBuildKotlinCompiler.kt` — bounded Kotlin project adapter: validates project sources/options, delegates compiler implementation to the managed compiler registry, then runs D8 over returned JVM classes into project-owned hot DEX; it does not embed Kotlin compiler authority;
- `android/app/src/main/java/com/riftos/app/RiftBuildManagedToolchains.kt` — generic project-owned payload/compiler registry resolver; validates confined paths, byte bounds and exact SHA-256 identities for compiler/runtime/VM payloads, and binds compiler ids to bounded engines (`native-buffer-v1` or `dex-json-v1`) through `riftbuild-hot.json`;
- `android/app/src/main/java/com/riftos/app/RiftManagedJvmToolService.kt` — isolated `:riftJvmToolHot` Binder process that exact-hash loads external APK/DEX tool payloads with a framework-only `DexClassLoader` parent and invokes the standard `public static String run(String requestJson)` ABI;
- `android/app/src/main/java/com/riftos/app/RiftppDynamicCompilerService.kt` — sole Rift++ native compiler worker, isolated in `:riftppCompilerHot`, for bounded exact-hash `native-buffer-v1` compiler payloads;
- `android/rift-managed-kotlin-tool/` — default seed compiler payload APK using the Android-patched Kotlin compiler; it is staged as data under `assets/riftbuild/compiler-seeds/` and can later be replaced by a project-managed compiler payload without changing RiftOS;
- `android/app/src/main/java/com/riftos/app/RiftBuildNativeToolchain.kt` — Native Compile V1 toolchain discovery, structured compiler argv execution, project-manifest validation and ARM32/ARM64 ELF output verification;
- `android/app/src/main/java/com/riftos/app/RiftBuildNativeApp.kt` — generic NativeActivity binary-manifest generation plus bounded project-asset materialization for normal native applications;
- `android/app/src/main/java/com/riftos/app/RiftApkV2Signer.kt` — Android-Keystore RSA key owner plus narrow APK Signature Scheme v2 encoder/verifier;
- `android/app/src/main/java/com/riftos/app/RiftBuildInstaller.kt` — exact-package PackageInstaller session/result/first-launch proof owner;
- `android/app/src/main/java/com/riftos/app/RiftAppDiagnosticBridge.kt` — allowlisted localhost-UDP diagnostic session/evidence owner for Rift++ proof/editor launches;
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — fixed native `riftbuild`, `riftpp-editor` and `riftcrash` command routing; the legacy `riftpp-host` compiler surface is retired;
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
- interpret project/source text as shell commands;
- invoke a shell command string for native compilation;
- execute a compiler outside the explicitly configured native-toolchain contract;
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
riftbuild toolchain-status
riftbuild toolchain-install-bundled
riftbuild managed-status <project>
riftbuild managed-payload <project> <id>
riftbuild managed-copy <project> <id> <output>
riftbuild compiler-status <project>
riftbuild compiler-run <project> <compiler-id> <request.json>
riftbuild kotlin-status
riftbuild kotlin-compile <project>
riftbuild riftpp-compile-hot <project> <payload-id> <source> <output> [capacity]
riftbuild compile-native <project> [arm32|arm64|universal]
riftbuild compile-object <project> <source.S> [arm32|arm64]
riftbuild extract-object-text <project> <object.o> [arm32|arm64]
riftbuild prepare-native-app <project>
riftbuild prepare-codynex-mc0 <codynex-root>
riftbuild prepare-codynex-mc1a <codynex-root>
riftbuild prepare-codynex-mc1b <codynex-root>
riftbuild prepare-codynex-m2-vm0 <codynex-root>
riftbuild prepare-codynex-m2b <codynex-root>
riftbuild prepare-codynex-mc2a <codynex-root>
riftbuild prepare-codynex-editor <codynex-root>
# then package the corresponding prepared ARM32 proof/editor subproject:
riftbuild pack <codynex-root>/native/mc0/apk-proof arm32
riftbuild pack <codynex-root>/native/mc1/apk-proof arm32
riftbuild pack <codynex-root>/native/mc1/apk-proof-b arm32
riftbuild pack <project> [arm32|arm64|universal]
riftbuild sign <unsigned-apk>
riftbuild verify <signed-apk>
riftbuild install-proof <signed-apk>
riftbuild install-status
riftbuild launch-proof
riftbuild runs [limit]
riftbuild artifacts [project]
```

### Bundled Android-host toolchain provisioning

RiftOS builds may carry a generated `assets/riftbuild/android-clang-v1.zip` plus ABI-matched host compiler/linker executables and a linker argv0 shim under generated JNI libs. `riftbuild toolchain-install-bundled` extracts only the data archive into `/C:/Toolchains/android-clang-v1` with path, file-count, per-entry and a 1 GiB total-byte bound, validates `toolchain.json`, atomically replaces any prior installation, and then reports normal `toolchain-status`.

The compiler itself remains in Android's extracted native-library directory (`native:libclang_exec.so`) so modern Android executable-storage rules are respected. Generated host dependencies are co-located there; RiftBuild sets `LD_LIBRARY_PATH` to that directory for the compiler process. Because Android packages the real `ld.lld` under the JNI-safe name `libld_lld_exec.so`, Builder also emits `libld_lld_shim.so`; the shim re-execs the sibling linker with `argv[0] = "ld.lld"` so LLD selects its GNU/ELF driver correctly. Toolchain argv supports `%COMPILER_DIR%` alongside `%TOOLCHAIN%` and `%SYSROOT%`, allowing the manifest to select that shim without shell command text. Builder evidence for source `fb83e531…` now proves both ABI host payloads and the bounded data archive can be generated; current source also fixes ZIP-entry path normalization to use Kotlin's valid escaped-backslash `Char` literal, with a focused source regression preventing the prior malformed four-backslash form from reaching Gradle again.

RiftOS Gradle consumes Builder-generated `build/generated/riftosJniLibs` and forces legacy/extracted JNI packaging; the Android manifest explicitly sets `android:extractNativeLibs="true"`. Source checkouts remain free of generated binary toolchain payloads.

### Native Compile V1 manifests

Toolchain provisioning is described by `/C:/Toolchains/android-clang-v1/toolchain.json`:

```json
{
  "schema": "riftbuild-android-clang-toolchain/1",
  "version": "clang-compatible",
  "source": "local-or-downloaded",
  "compiler": "bin/clang++",
  "sysroot": "sysroot",
  "args": ["-resource-dir=%TOOLCHAIN%/resource", "--ld-path=%COMPILER_DIR%/libld_lld_shim.so"]
}
```

`compiler` and `sysroot` may be relative to that toolchain root; `absolute:/...` is also accepted, and a bundled executable may use `native:<filename>`. Bounded `args` are toolchain-owned argv entries for resource/libc++/linker setup and are never interpreted by a shell; `%TOOLCHAIN%`, `%SYSROOT%`, and `%COMPILER_DIR%` expand to the resolved toolchain, sysroot, and executable-native-library directories before process launch. Readiness requires a real executable compiler and sysroot. Android-host compatibility is proven only when `compile-native` actually succeeds.

Each native project opts in with `<project>/rift-native.json`:

```json
{
  "schema": "riftbuild-native-project/1",
  "library": "proto_llm",
  "sources": ["native/proto.cpp"],
  "includeDirs": ["native/include"],
  "libraries": ["android", "log"],
  "cxxStandard": "c++20",
  "api": 26,
  "optimization": "O2"
}
```

Sources and include directories are project-relative and confined to the project. `libraries` is a bounded list of linker library names lowered to `-l<name>` (for example Android NativeActivity code can request `android` and `log`). Native Compile V1 owns `-o`, target/sysroot selection, PIC/shared-library mode and linker identity flags; project text is not parsed as command text. Universal compilation emits and ELF-verifies both `lib/arm64-v8a/lib<library>.so` and `lib/armeabi-v7a/lib<library>.so` under `build/riftbuild/prepared/`.

Native Compile V2 extends that contract without adding raw linker-argument authority. A `riftbuild-native-project/2` manifest may use ABI-bound prebuilt `objects` and project-local static `archives`. ET_REL objects are checked for ELF class, machine, endianness and `ET_REL` type before launch; project archives are bounded and checked for Unix `ar` magic. Archive entries may request an explicit `wholeArchive` boundary, which RiftBuild lowers only to paired `-Wl,--whole-archive` / `-Wl,--no-whole-archive` arguments.

`riftbuild compile-object <project> <source.S> [arm32|arm64]` is a separate bounded assembly-object authority. It does not require or synthesize `rift-native.json`, accepts only a confined project-relative `.S`/`.s` source, launches the configured compiler with structured argv and fixed `-c` object mode, writes a content-addressed object under `build/riftbuild/objects/<abi>/`, and immediately applies the same ELF class, machine, little-endian, and ET_REL verification used for manifest-linked objects. It does not expose arbitrary compiler or linker flags and does not alter `compile-native` shared-library semantics.

`riftbuild extract-object-text <project> <object.o> [arm32|arm64]` is a separate bounded ET_REL extraction authority for exact machine-code proofs. It re-verifies the object ABI, parses the ELF section table directly, rejects any non-empty SHT_REL/SHT_RELA section, requires exactly one executable `.text` SHT_PROGBITS section, caps both object and extracted text sizes, and emits canonical raw `.bin` plus lowercase continuous `.hex` artifacts under `build/riftbuild/blobs/<abi>/`. This path performs no linking or relocation and is intended for relocation-free seeds such as Codynex VM2.

A project containing both `rift-native.json` and `rift-app.json` is a first-class manifest-native RiftBuild project. Validation reuses the same bounded native/app manifest parsers used by compile and prepare; it does not require placeholder Gradle files or a source AndroidManifest. Target-specific plans then inspect only the requested ABI in `build/riftbuild/prepared`, so an explicit `arm32` package does not require an unrelated arm64 library.

Generic NativeActivity packaging opts in with `<project>/rift-app.json`:

```json
{
  "schema": "riftbuild-native-app/1",
  "package": "com.proto.llm",
  "library": "proto_llm",
  "versionCode": 1,
  "versionName": "0.1.0",
  "minSdk": 26,
  "targetSdk": 36,
  "assetsDir": "assets",
  "permissions": []
}
```

`riftbuild prepare-native-app <project>` preserves that v1 NativeActivity behavior exactly: it emits a bounded Android binary manifest for an exported `android.app.NativeActivity`, cross-checks its library name against `rift-native.json` when present, and copies the bounded project asset tree into `build/riftbuild/prepared/assets/` without touching already-compiled ABI libraries. `permissions` is optional, bounded and deduplicated; the current allowlist contains exactly `android.permission.INTERNET`. This remains the intended lane for custom script source/bytecode such as Proto-LLM runtime assets and historical Rift++ NativeActivity proofs.

`riftbuild-native-app/2` is the bounded app-schema extension point for the frozen Rift++ Android adapter. It accepts either the legacy `activity: "native-activity"` path with a blank `managedRuntime`, or `activity: "riftpp-adapter"` with the fixed `managedRuntime: "riftpp-android-adapter/1"`. The adapter is built from the dedicated `:riftpp-adapter-runtime-bundle` module and contains only Android lifecycle/surface ownership plus native-library loading/JNI forwarding. `RiftppActivity` does not own renderer state, editor state, application semantics or compiler/build authority. RiftOS extracts and validates the adapter `classes*.dex` files into signed generated assets, and `prepare-native-app` copies only that fixed runtime into the prepared APK root, sets `android:hasCode="true"`, and launches `com.riftpp.android.RiftppActivity` while `android.app.lib_name` continues to name the Rift++ runtime library.

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

The legacy-editor ET_REL preflight command is `riftpp-editor native-preflight <elf-path> [required-symbol]`. The optional symbol defaults to `android_main` for compatibility, while JNI-backed runtimes can require their actual exported JNI entry point instead of adding a fake NativeActivity symbol.

### PackageInstaller confirmation handoff

RiftBuild installation remains explicitly user-confirmed. The PackageInstaller session status `IntentSender` targets the private `RiftBuildInstallReceiver` through `PendingIntent.getBroadcast(...)`, because status delivery proved more reliable than an Activity-only callback on the live device. When Android reports `STATUS_PENDING_USER_ACTION`, the receiver retains the system confirmation `Intent` in-process and records `pending-user-action`. If RiftOS already owns a focused `MainActivity`, the confirmation is launched immediately from that Activity; otherwise `MainActivity.onResume()` / regained window focus consumes the retained intent and launches the system installer from a real foreground Activity. Terminal success/failure statuses clear retained confirmation state.

This hybrid path intentionally combines reliable receiver delivery with foreground Activity presentation. It does not add silent-install authority: `USER_ACTION_REQUIRED`, APK v2 verification, package allowlisting and Android user confirmation remain mandatory. Rift++ packages `com.riftpp.hello`, `com.riftpp.editor`, `com.riftpp.editor.nativev1` and `com.riftpp.editor.adapterr1` are fixed allowlisted identities in addition to the bootstrap proof package; this does not bypass signature verification or user confirmation. For bridge-supported Rift++ packages, the installer starts a bounded diagnostic session before exact launch. If the RiftOS process dies while confirmation is pending, the retained nested intent is lost and `install-proof` must be retried rather than attempting to persist/replay a system-owned confirmation intent.

## Rift++ managed compiler hot path

Rift++ compiler authority is project-owned and hot-swappable. RiftBuild does not keep generation-specific Rift++ compiler transactions or proof packagers.

Supported compiler surfaces:
- `riftbuild managed-status <project>`, `managed-payload`, and `managed-copy` for exact-hash project payloads;
- `riftbuild compiler-status <project>` and `compiler-run <project> <compiler-id> <request.json>` for the generic compiler registry;
- `riftbuild riftpp-compile-hot <project> <payload-id> <source> <output> [capacity]` for bounded native-buffer compiler payloads in the isolated `:riftppCompilerHot` process;
- `riftbuild kotlin-compile <project>` for Kotlin projects using an external managed compiler payload through the isolated `:riftJvmToolHot` process;
- `riftbuild prepare-native-app <project> -> pack -> sign -> verify -> install-proof` for generic application packaging.

The legacy `riftpp-host` / `:riftppCompiler` / `RiftppCompilerService` lane is retired. The V0 direct-ELF bridge, Seed0 proof APK, App0 host, and `prepare-riftpp-v0`, `prepare-riftpp-seed0-arm64`, `prepare-riftpp-app0`, and `prepare-riftpp-editor` special-case routes are removed from current source. Regression coverage explicitly fails if those surfaces reappear.

Historical R1–R8, Seed0, Stage1, S2/S3, direct-ELF, App0, and temporary editor-bootstrap proof details remain evidence in `docs/PATCH_HISTORY.md`; they are not current RiftBuild APIs.

## Codynex C0 .cx editor local packaging lane

The current editor remains canonical under local Codynex source:

`external/editor/`

The editor is a normal Android development shell for `.cx` files. Compilation is not performed by the old MC2-A Source0 compiler. Instead, the editor calls the bounded RiftOS ContentProvider authority `com.riftos.app.codynexcompiler`; RiftOS verifies the caller package/signing certificate and runs the fixed active C0 compiler through its existing headless QuickJS host. The returned artifact is bounded VM1 bytecode. Preview remains local to the editor through `libcodynex_editor_vm.so` and the frozen VM1 seed.

RiftOS carries a SHA-bound compiled packaging payload only so the phone can package the editor without a remote editor build or arbitrary on-device Gradle execution.

`riftbuild prepare-codynex-editor <codynex-root>` must:

- verify the exact local Codynex editor source/project hashes before packaging;
- require a normal code-bearing Activity project with launch activity `.MainActivity`;
- extract `classes*.dex` from the installed RiftOS APK under bounded per-entry/total limits;
- extract the ARM32 `libcodynex_editor_vm.so` preview bridge from the installed RiftOS APK;
- materialize a bounded binary manifest for package `com.codynex.editor` and activity `com.codynex.editorapp.MainActivity`;
- include explicit package visibility for `com.riftos.app`;
- copy the canonical local VM1 hex used by Preview;
- require `classes.dex` for code-bearing packages;
- re-hash every materialized authority artifact;
- feed the existing local `pack -> sign -> verify -> install-proof` chain;
- never mutate Codynex source and never grant the editor shell, process, or network authority.

The mirrored editor source inside RiftOS is packaging payload, not Codynex source authority. RiftOS build validation pins that payload to the canonical local Codynex hashes so drift fails closed. The current packaging implementation may still carry inert historical MC2-A assets for compatibility, but the editor runtime no longer reads them.

## Codynex MC0 local proof lane

MC0 reuses the existing prepared-artifact pipeline instead of using Tmpbuilder or GitHub as a build transport.

`riftbuild prepare-codynex-mc0 <codynex-root>` is ARM32-only and must:

- read `native/mc0/arm32/mc0_seed.hex` from the Codynex project;
- decode exactly 172 bytes;
- require SHA-256 `3276dcbf29704b1ba7d9d331e7891ceff10d85b16bb7688c62273aeaa3ca311e`;
- validate the source-oracle project at `native/mc0/apk-proof`;
- extract `lib/armeabi-v7a/libcodynex_mc0_host.so` from the installed RiftOS APK;
- require the extracted host to be an ARMv7 little-endian ET_DYN ELF;
- materialize a bounded binary AndroidManifest for package `com.codynex.mc0proof`;
- write the host only as `lib/armeabi-v7a/libcodynex_mc0_host.so`;
- write the exact compiler only as `assets/mc0_seed.bin`;
- re-hash both materialized files;
- record that compiler authority remains the seed asset.

The MC0 host is test equipment. Its allowed responsibilities are limited to:

- reading the exact seed asset;
- RW mapping and copying the seed;
- changing the mapping to RX before execution;
- providing bounded source/output buffers;
- calling the raw compiler ABI;
- checking frozen proof vectors;
- changing emitted-code memory from RW to RX before execution;
- executing generated code;
- reporting PASS/FAIL.

The host must not parse Codynex source, emit target instructions, repair compiler output, or substitute another compiler implementation.

The host library is built as part of RiftOS for both configured RiftOS ABIs, but the MC0 proof preparer extracts and packages only the `armeabi-v7a` image. This forces the proof APK into a 32-bit process where the A32 seed is executable.

No general native compiler is implied by this lane. MC0 is already machine code; RiftBuild only packages the bounded test host and exact machine-code asset.

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
- installation is restricted to RiftBuild's fixed proof-package allowlist: `com.riftpp.nativeproof`, `com.codynex.mc0proof`, `com.codynex.mc1aproof`, and `com.codynex.mc1bproof`;
- RiftOS declares `REQUEST_INSTALL_PACKAGES` and uses Android `PackageInstaller`, never raw package-manager shell commands;
- normal Android unknown-source trust/user confirmation remains mandatory;
- PackageInstaller commit/result callbacks are delivered to the private `RiftBuildInstallActivity`, not a background broadcast callback, so `STATUS_PENDING_USER_ACTION` can surface Android's confirmation UI from a foreground Activity;
- `RiftBuildInstallReceiver` remains only for bounded first-launch evidence;
- install status is persisted under RiftBuild system state;
- after successful installation, the proof launcher targets only exported `android.app.NativeActivity` for the allowlisted package recorded by the install session;
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
7. install — user-confirmed PackageInstaller + bounded proof-package allowlist and exact NativeActivity launch owner defined by this contract.

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
- signing/install success is claimed without the independent v2 verifier, the bounded proof-package allowlist (`com.riftpp.nativeproof`, `com.codynex.mc0proof`, `com.codynex.mc1aproof`, and `com.codynex.mc1bproof`), or Android-managed user confirmation;
- RiftBuild silently enables the experimental CLI;
- MCP catalog expands just to expose build internals.

## Fix map

- local build controller/package stage -> `RiftBuildLocalExecutor.kt`;
- native C/C++ compiler execution -> `RiftBuildNativeToolchain.kt`;
- generic NativeActivity manifest/assets preparation -> `RiftBuildNativeApp.kt`;
- shell command routing -> `RiftNativeShell.kt`;
- installed-app capability surface -> `RiftBrowserAppHost.kt`;
- Android source snapshot -> `android/app/build.gradle.kts`;
- source ownership -> `docs/SOURCE_OWNERSHIP.md`;
- source/build validation -> `scripts/test-riftbuild-native.mjs` + build-validation docs;
- Rift++ direct ELF emission -> `workspace/rift++/standalone/android-native-r1` (separate project authority); RiftOS admits and executes exact pinned emitter/source identities but does not parse or emit ELF.

## Validation

Promotion requires:
- focused source test proves workspace/output confinement and confines process execution to `RiftBuildNativeToolchain` structured compiler argv (no shell command string);
- existing `build.local` remains capability-gated;
- `src/riftbuild.js` remains unpackaged reference code;
- exact Kotlin snapshot includes the native owner;
- source ownership and subsystem docs are synchronized;
- `npm run check` remains green;
- the already-proven validation/materialization/manifest/package path must retain its installed-device regression evidence;
- the newer signer/verifier/installer/launch source must pass Builder compilation plus real on-device sign → verify → install → launch proof before that chain is called installed/live.


## Codynex MC1-A raw machine proof lane

MC1-A is a separate additive proof lane. MC0 remains frozen as the previous-stage oracle.

Command:

`riftbuild prepare-codynex-mc1a /workspace/Codynex`

The preparer:

- decodes only `native/mc1/arm32/mc1a_seed.hex`;
- requires exactly **236 bytes**;
- requires SHA-256 `2ef7054e533bfafaefb0fcc14b9cd41cd05aceeec58eeeb335fc6aef4e88ba1a`;
- validates `native/mc1/apk-proof`;
- extracts only `lib/armeabi-v7a/libcodynex_mc1a_host.so` from the installed RiftOS APK;
- verifies the extracted host is ARM32 ELF;
- generates a bounded binary manifest for `com.codynex.mc1aproof`;
- packages the exact raw compiler only as `assets/mc1a_seed.bin`;
- records host/seed hashes and anti-contamination ownership.

The MC1-A host is test equipment only. It may map/invoke the exact compiler, provide bounded buffers, execute emitted code, compare frozen vectors and report PASS/FAIL. It must not parse decimal source, emit target instructions, repair compiler output or substitute another compiler.

The proof remains ARM32-only. General native compilation is still not implied by this lane.


## Codynex MC1-B / MC1.2 raw machine proof lane

MC1-B is a separate additive proof lane. MC1-A remains frozen as the previous-stage oracle.

Command:

`riftbuild prepare-codynex-mc1b /workspace/Codynex`

The preparer:

- decodes only `native/mc1/arm32/mc1b_seed.hex`;
- requires exactly **552 bytes**;
- requires SHA-256 `4f4a7305900547d949831fc4cfc6c6c0f747edd7ab525adfb8a1488a6ca304be`;
- validates `native/mc1/apk-proof-b`;
- extracts only `lib/armeabi-v7a/libcodynex_mc1b_host.so` from the installed RiftOS APK;
- verifies the extracted host is ARM32 ELF;
- generates a bounded binary manifest for `com.codynex.mc1bproof`;
- packages the exact raw compiler only as `assets/mc1b_seed.bin`;
- records host/seed hashes and anti-contamination ownership.

The MC1-B host is test equipment only. It maps/invokes the exact compiler, checks the frozen **96-case assertion surface**, executes the emitted native function and requires exact 12-byte `MOV + ADD + BX` output. It must not parse source, emit target instructions, constant-fold the expression, repair compiler output or substitute another compiler.

The generated runtime result may reach 510 even though each source literal remains u8. The exact emitted `ADD r0,r0,#right` is part of the proof contract.

This lane remains ARM32-only and is not promoted until the exact 552-byte seed passes on real hardware.
