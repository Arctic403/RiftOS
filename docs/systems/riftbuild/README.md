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
- `android/app/src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt` — native build controller, direct-ELF bridge materializer, binary-manifest/package orchestration and bounded sign/verify/install command routing;
- `android/app/src/main/java/com/riftos/app/RiftBuildNativeToolchain.kt` — Native Compile V1 toolchain discovery, structured compiler argv execution, project-manifest validation and ARM32/ARM64 ELF output verification;
- `android/app/src/main/java/com/riftos/app/RiftBuildNativeApp.kt` — generic NativeActivity binary-manifest generation plus bounded project-asset materialization for normal native applications;
- `android/app/src/main/java/com/riftos/app/RiftApkV2Signer.kt` — Android-Keystore RSA key owner plus narrow APK Signature Scheme v2 encoder/verifier;
- `android/app/src/main/java/com/riftos/app/RiftBuildInstaller.kt` — exact-package PackageInstaller session/result/first-launch proof owner;
- `android/app/src/main/java/com/riftos/app/RiftAppDiagnosticBridge.kt` — allowlisted localhost-UDP diagnostic session/evidence owner for Rift++ proof/editor launches;
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — fixed native `riftbuild`, `riftpp-host` and `riftcrash` command routing;
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
riftbuild compile-native <project> [arm32|arm64|universal]
riftbuild prepare-native-app <project>
riftbuild prepare-riftpp-v0 <project> [arm32|arm64|universal]
riftbuild prepare-riftpp-seed0-arm64 <riftpp-root>
riftbuild prepare-riftpp-app0 <riftpp-root> <app-dir>
riftbuild prepare-riftpp-editor <riftpp-root>
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

`riftbuild prepare-native-app <project>` emits a bounded Android binary manifest for an exported `android.app.NativeActivity`, cross-checks its library name against `rift-native.json` when present, and copies the bounded project asset tree into `build/riftbuild/prepared/assets/` without touching already-compiled ABI libraries. `permissions` is optional, bounded and deduplicated; the current allowlist contains exactly `android.permission.INTERNET`. This is the intended lane for custom script source/bytecode such as Proto-LLM runtime assets and for the current Rift++ R1 localhost diagnostic proof.

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

### PackageInstaller confirmation handoff

RiftBuild installation remains explicitly user-confirmed. The PackageInstaller session status `IntentSender` targets the private `RiftBuildInstallReceiver` through `PendingIntent.getBroadcast(...)`, because status delivery proved more reliable than an Activity-only callback on the live device. When Android reports `STATUS_PENDING_USER_ACTION`, the receiver retains the system confirmation `Intent` in-process and records `pending-user-action`. If RiftOS already owns a focused `MainActivity`, the confirmation is launched immediately from that Activity; otherwise `MainActivity.onResume()` / regained window focus consumes the retained intent and launches the system installer from a real foreground Activity. Terminal success/failure statuses clear retained confirmation state.

This hybrid path intentionally combines reliable receiver delivery with foreground Activity presentation. It does not add silent-install authority: `USER_ACTION_REQUIRED`, APK v2 verification, package allowlisting and Android user confirmation remain mandatory. Rift++ packages `com.riftpp.hello`, `com.riftpp.editor` and `com.riftpp.editor.nativev1` are fixed allowlisted identities in addition to the bootstrap proof package; this does not bypass signature verification or user confirmation. For bridge-supported Rift++ packages, the installer starts a bounded diagnostic session before exact launch. If the RiftOS process dies while confirmation is pending, the retained nested intent is lost and `install-proof` must be retried rather than attempting to persist/replay a system-owned confirmation intent.

## Rift++ Android Native R1/R2/R3 load, callback and first-frame gates

R1 load/entry and R2 NativeActivity callback ownership are installed-device proven. Run 484 / source `7e6f83738d4d8b343cb6378edbb5c7d32a0cf6ef` installed `0.4.0-riftpp-r2-window`; Android invoked the Rift++-installed `onNativeWindowCreated` callback twice and the diagnostic bridge received two valid stage-6 `window-callback` packets from target PID 27663 with non-zero activity/window pointers.

The promoted R2 base remains the 972-byte Bionic-valid ARM32 ET_DYN object SHA-256 `d9669d97c6f0f0225b8624818dc9f2f0dad4611ded757ac48dfea1b9cba06d46`. R3 is deliberately a second Rift++ stage rather than a mutation of the proven R1/R2 emitter:

```text
riftpp-host s3-android-r3 <riftpp-root>
  -> regenerate exact promoted R2 ELF
  -> frozen S3 compiles elf32-r3-frame-linker.arm32.r3.hex
  -> 4,192-byte Rift++ frame linker
  -> linker consumes exact 972-byte R2 ELF
  -> 1,196-byte R3 rendering ELF
  -> standalone/android-native-r1/libriftpp_editor_native_r3.so
```

The active R3 linker source transport is 4,437 bytes of fixed-record text, SHA-256 `18782a0cb8719b04fcac338667ca99f22e58d0e3c52b5a09d18ea4773c0173b6`; decoded S3 source is 2,088 bytes (260 body records plus the S3 header). The generated linker is fixed at 4,192 bytes, still well within the generic 64 KiB compiler-host source/output bounds. The earlier 4,182-byte / 245-body-record linker is retained as `elf32-r3-frame-linker.loader-failed.arm32.r3.hex` for failure evidence.

The 1,196-byte R3 ELF uses four program headers: the existing RX load, `PT_DYNAMIC`, non-executable GNU stack, and a distinct RW load used for the GOT. It intentionally contains no W+E `PT_LOAD`. Dynamic metadata adds `DT_NEEDED libandroid.so`, imports only `ANativeWindow_setBuffersGeometry`, `ANativeWindow_lock`, and `ANativeWindow_unlockAndPost`, and resolves them with three `R_ARM_GLOB_DAT` relocations at virtual GOT addresses `0x1200`, `0x1204`, and `0x1208`.

The R3 callback body is 152 ARM32 bytes. It keeps the proven `ANativeActivity_onCreate` callback-table ownership, requests RGBA8888, locks the real `ANativeWindow`, fills the full stride × height buffer with the fixed orange frame color, and calls `ANativeWindow_unlockAndPost`. This is only a first-pixel/frame proof: no editor widgets, text system or input path are promoted yet.

RiftOS remains transport/execution/evidence authority only. `RiftppCompilerService` verifies fixed sizes/identities and executes exact pinned compiler/linker images in the private compiler process; `RiftNativeShell` publishes the returned bytes; RiftBuild packages/signs/verifies/installs the static NativeActivity wrapper. RiftOS does not parse S3 opcodes, emit target ARM instructions, parse/construct target ELF semantics or implement target renderer behavior.

The first version-5 `0.5.0-riftpp-r3-frame` APK installed successfully but crashed on launch. Postmortem inspection of the exact 1,196-byte ELF SHA-256 `d825e50988a3265e05b8b8c56b46fa915a260ecb212488085f72f954f3f5c9e0` found `0xA5` guard poison left in mandatory zero-valued loader metadata: dynsym entry 0, undefined-import values/sizes, SysV hash-chain terminators, and the final `DT_NULL`. The corrected Rift++ linker explicitly zeroes those fields while preserving the guard elsewhere. Build 488 then exposed a separate generic-host issue before that corrected linker could execute: the 4,192-byte linker output crossed the device's one-page backing span and `nativeCompile` rejected with `-96`. The host was corrected to use multi-page `GuardedSpan` source/output buffers while preserving outer guard pages and prefix-canary checks.

Builder run 490 / source `18e1b5bfd6c91efee110d6152fbdafda8843e866` cleared both gates. Frozen S3 compiled the corrected 2,088-byte source to the exact 4,192-byte linker SHA-256 `98087752725238853d58d124930dcb25a0d96120899d5b2e07f69527137b9a8d`, which produced the exact 1,196-byte R3 ELF SHA-256 `44f8b266aef910acaaedfe3a5f6cf7b9f1e029acd6e1c2e310410a22568be28e`. Numeric verification confirmed all fourteen formerly poisoned mandatory fields were zero, the dynamic table ended in `DT_NULL`, dynsym null/import fields were valid, SysV hash terminators were zero, relocations remained exact, and the RX/RW program headers remained separate with no W+E load. RiftBuild packaged, APK-v2 signed and independently verified signed artifact SHA-256 `cbd5bb384f97aee456bef6fdae286c9c2c939266d74943405d4106fbddd1d6be`. Android install session `1663217197` reported `INSTALL_SUCCEEDED` and `launch-proven`, and direct on-device screenshot evidence showed the full orange frame. R3 first-frame rendering is promoted. The R4 editor-surface gate is now promoted through the separate `TRANSACTION_S3_UI_PATCH` / `riftpp-host s3-android-r4 <riftpp-root>` lane. Builder run 492 / source `2b94042e6fd229ee7aba4813ab99857542dee262` compiled the exact 528-byte decoded patcher SHA-256 `9cf4f6c7670d50f2b6caaeca2f24d20949811291fbe0dfdcbad3a104c78332af` from 1,122-byte transport SHA-256 `09afaeda7cbf30281718ec6e354838e75be3d6228297f0b4b70f815253610706` under frozen S3 to a 1,072-byte native patcher SHA-256 `82e6aa4e82426c7d1cc2392c3a52d5f46f5604873f2fd774a0ef6377b6d5d57d`. Applied only to the exact promoted R3 ELF SHA-256 `44f8b266aef910acaaedfe3a5f6cf7b9f1e029acd6e1c2e310410a22568be28e`, it produced the 1,196-byte R4 ELF SHA-256 `90dc170d574f9d3b0947cccb013b82f5a2bf0b3c3b65013e0fc5050f4da76f9a`. Exact byte comparison showed only 68 changed bytes in the renderer-tail/size-field allowance; program headers, dynamic table, SysV hash and relocations stayed byte-for-byte identical. VersionCode 6 / `0.6.0-riftpp-r4-topbar` was APK-v2 signed and independently verified as SHA-256 `b11fc25040c64fbca054e050cffedffd1612d06935780c9a4b7da109592538c5`; Android session `418510229` reported `INSTALL_SUCCEEDED`, and direct on-device screenshot evidence showed the dark charcoal editor bar over the preserved orange frame. R4 is promoted; the next gate is Rift++ text/glyph rendering.

## v0.1 project inspection

The controller recognizes ordinary Android/Gradle markers but does not claim Gradle has executed.

For the current Rift++ native proof it can verify:
- `settings.gradle.kts`;
- root/app Gradle files;
- `app/src/main/AndroidManifest.xml`;
- NativeActivity declaration;
- `android.app.lib_name`;
- bounded `uses-permission` generation from `rift-app.json.permissions`; the active permission allowlist is exactly `android.permission.INTERNET`, and the current R1 diagnostic wrapper requests only that permission for localhost UDP;
- ARMv7 and AArch64 generated sources;
- declared dual ABI filters.

The verifier records bounded source SHA-256 identities where useful.

## Rift++ seed0 ARM64 proof APK

`riftbuild prepare-riftpp-seed0-arm64 <riftpp-root>` is the current proof lane for the machine-code seed, separate from the retained legacy direct-ELF bridge.

It:
- requires the canonical `compiler/compiler.arm64.hex` text identity and decoded 276-byte SHA-256 `b1f33b940d2ac199f5e38c1c621cd8b27ed15dd3a60fcb85daad7b7154b2ee0c`;
- extracts only `lib/arm64-v8a/libriftpp_seed0_arm64_proof.so` from the installed universal RiftOS APK;
- packages an arm64-only `com.riftpp.nativeproof` NativeActivity with `assets/compiler.bin`;
- never packages an ARM32 fallback, so install/launch is itself an AArch64-userspace gate;
- keeps the proof host syntax-blind: the host does not parse Rift++ or emit replacement ARM instructions;
- checks the five exact seed bundles against the already-proven cross-host oracle, executes all five generated ARM64 payloads, repeats `ret 42` for determinism, and checks the documented malformed/reduced-capacity rejection set;
- reports only a bounded NativeActivity PASS/FAIL title suitable for readback through the existing local UI agent.

After preparation, use the normal bounded pipeline:

```text
riftbuild pack proofs/riftpp-seed0-arm64 arm64
riftbuild sign <unsigned-apk>
riftbuild verify <signed-apk>
riftbuild install-proof <signed-apk>
riftbuild launch-proof
```

If Android rejects the arm64-only APK as ABI-incompatible, that is device/userspace evidence rather than a Rift++ compiler failure.

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

## Rift++ App0 / U0 generated-runtime lane

Status: **UNIVERSAL U0 CONTRACT LOCKED / SOURCE IMPLEMENTED — BUILDER + REAL-DEVICE FREEZE PROOF PENDING**

App0 is the first Rift++ application lane that treats runtime support as a generated per-program result instead of one monolithic Rift++ runtime. U0 is the reusable universal-ABI baseline produced by that lane.

Command: `riftbuild prepare-riftpp-app0 <riftpp-root> <app-dir>`.

The fixed proof app is `rift++/examples/hello`.

Authorities remain separated: application source is `<app-dir>/program.tig0`; compiler authority is `<riftpp-root>/compiler/tig0/compiler_seed.hex`; runtime requirements authority is emitted `program.bin`; package identity is `<app-dir>/riftapp.json`; and both ABI host payloads are extracted from the installed RiftOS APK.

RiftBuild hosts the self-hosted 4,788-byte TIG0 compiler in its bounded VM1 interpreter. That compiler host is execution equipment only: it must not parse TIG0 into replacement instructions.

After compilation, RiftBuild validates every emitted VM1 instruction and derives support requirements from the bytecode. Unknown opcodes, malformed operands, invalid branch targets, or unsupported runtime requirements fail closed.

For U0 Hello, one 216-byte / 54-instruction VM1 program is shared by both ABIs. The runtime plan selects `core.vm1.arm64` as canonical/default, `core.vm1.arm32` as compatibility, plus `io.output.bytes`, `android.nativeactivity`, and `android.display.text`. Source input, scratch, file, network, database, task, graphics, audio, and general application heap support remain absent.

The prepared universal package contains binary `AndroidManifest.xml`, `lib/arm64-v8a/libriftpp_app0_host.so`, `lib/armeabi-v7a/libriftpp_app0_host.so`, and one shared `assets/program.bin`. It does not package the old ARM32 machine-code VM seed. That seed remains Bootstrap0/historical evidence, not U0 application runtime data.

Both native libraries are compiled from the same `riftpp_app0_host.cpp` runtime source and implement the same bounded VM1 instruction semantics. ARM64 is primary, ARM32 is compatibility, and Android selects the matching ABI automatically. ABI differences may change machine implementation only, never valid Rift++ program behavior.

App0 remains restricted to package `com.riftpp.hello`, `target=universal`, and `presentation=text` while the proof installer remains allowlisted.

Promotion requires rebuilt RiftOS containing both hosts, followed by `prepare-riftpp-app0 -> pack universal -> sign -> verify -> install-proof -> launch-proof`. The universal APK must contain both ABI libraries and the ARM64 device must display exactly `Hello from Rift++`. ARM32 source/build conformance remains mandatory, with hardware proof added when an ARM32 target is available.

## Rift++ temporary Kotlin editor bootstrap packaging lane

Status: **SOURCE IMPLEMENTED — REBUILT-RIFTOS DEVICE PROOF PENDING**

Command: `riftbuild prepare-riftpp-editor <riftpp-root>`.

This is a deliberately temporary Android/Kotlin packaging lane used only to prove the
standalone Rift++ editor loop without repeating NativeActivity lifecycle/framebuffer
work. The canonical source remains under
`rift++/standalone/editor/android/`; RiftOS carries SHA-bound mirrored Kotlin/JNI
payload only so those classes and the tiny execution bridge exist in its compiled DEX/APK
for local extraction.

Preparation must:

- verify exact hashes for the local Rift++ Kotlin shell, JNI bridge and Android project metadata;
- verify the frozen S3 ARM32 compiler plus the Rift++ frontend and preview record sources;
- require package `com.riftpp.editor` and launch activity `com.riftpp.editor.MainActivity`;
- extract `classes*.dex` and `libriftpp_editor_bridge.so` from the rebuilt installed RiftOS APK;
- materialize only the ARM32 proof assets under `assets/riftpp/`;
- emit a code-bearing binary Android manifest;
- re-hash every materialized authority artifact;
- feed the normal local `pack -> sign -> verify -> install-proof` chain;
- never let Kotlin parse `.riftpp`, emit RPA1, interpret RPA1, or become compiler/runtime authority.

After boot + edit/save/load + compile + preview are proven, this Kotlin shell is replaced
by a native Rift++ app-specific editor/runtime slice. The Kotlin bootstrap is not
promotable S4 architecture.

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
