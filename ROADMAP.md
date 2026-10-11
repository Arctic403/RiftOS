# RiftOS Roadmap

## 2026-10-10 — Lift narrow RAPP executable limit, keep bounded runtime state

The 1 MiB **runtime.js executable source** ceiling is now **8 MiB**; ZIP package limit is **16 MiB** (both compressed artifact size and extracted total). The separate **1 MiB mutable program/state** and bounded event/result queues are deliberately unchanged until measured, end-to-end runtime transport tests justify larger values. `RiftRappManager`, QuickJS loader, and independent Core source stay aligned via new `scripts/test-rapp-payload-limits.mjs` plus signed Builder static gates. This removes source-size rejection; it does **not** make existing compiler runs faster. Requires next manual signed Android host APK for effect; installed #702 is unchanged, and incomplete external Core v0.2 remains inactive.

## 2026-10-10 — Real external Core session engine v0.2 milestone

Installed signed #702 host is healthy; ordinary generic builder `build.local` permission and registered Kotlin/D8 readiness were physically confirmed. `external-components/IndependentRappSessionsV1.kt` now owns Core RAPP session generations, focus, bounded event queue, frame validation, durable state, and explicit denied capability-effect continuations, with an independent `IndependentJavascriptVmV1` interface and sealed runtime lookup. Compiled `core.dex` v0.2.0 with registered Kotlin + D8 (**2,603,876 bytes, SHA c1b44f56481e…**), exported strict manifest, and preserved prior v0.1 Core under `/D:/Builds/Components/core/previous/`. The runtime VM SHA pin remains empty, so RAPP JavaScript BOOT is correctly blocked and no Core/Shell staging/activation is permitted. Next: implement independent JS runtime and capability broker, prove real RAPP sessions in an isolated test without importing embedded APK execution, then complete graphical Shell parity and physical recovery gates.

## 2026-10-10 — Independent component compiler proof after #701 host acceptance

The #701 user-manually signed RiftOS APK passed embedded Core/Shell/supervisor compatibility, graphical existing RAPP and Admin UI visibility. Separately compiled Core V1 and graphical Shell V1 **actually completed Kotlin+D8** as independent DEX and strict manifests under `/D:/Builds/Components`. Compiler source and independent RAPP producer are versioned at `external-components/` outside the APK. Their known hashes and limits: [build evidence](external-components/BUILD_EVIDENCE.md). The Core still requires a true independent JS runtime/event/capability/installer before any production activation; the Shell still requires built-in desktop/system-app and on-device rendering parity. Registered D8 bundles Kotlin stdlib; the host protected DEX verifier must admit narrowly scoped Kotlin support classes while denying APK Core definitions, and thus needs **one more manually signed APK build before staged candidate proof**. Finish real external features and regression gates first; keep current embedded fallback. Do not count standalone DEX compilation as completed H/C/R/S/F device proof.

## 2026-10-10 — Main-branch protected Core/Shell installation and N-1 failback SOURCE integration

Integrated `RiftProtectedComponentManifest` (strict external component ABI, digest/provenance and fixed Core/Shell .dex location), `RiftProtectedDexVerifier` (bounded DEX type/class inventory and no embedded Core execution types), `RiftProtectedComponentInstaller` (content-addressed stage, per-SHA qualification, exact pending pointer transaction and explicit post-device SHA/PID promotion), and `RiftProtectedRevisionRecovery` (quarantine failed SHA, verify last accepted external artifact/receipt, restore N-1 pointer or embedded, clear stale boot marker). Native Admin Approvals now has separate one-use stage, next-start activation and running-SHA acceptance actions using the OS-attested graphical Binder caller. Persisted fatal Core SHA/PID Android 11+ crash history can trigger prior external rollback before next bootstrap; Shell process absence can restore external Shell N-1 before relaunch. Accepted revisions restart normally without falsely retaining an unaccepted boot marker. Shell recovery/admin/runtime diagnostics now use the selected Core execution view rather than initializing embedded Core. Core/Shell source owners and independent artifact protocol: [protected artifact format](docs/systems/android-host/PROTECTED_COMPONENT_FORMAT.md). **Not yet compiled/installed/device proven; this is still NOT complete standalone Core or full graphical Shell implementation.** No signed APK automatically dispatched, backup remains recovery-only and active branch remains `main`.

## 2026-10-10 — Main-branch Core/Shell integration checkpoint (SOURCE ONLY)

The user confirmed the GitHub backup is a **recovery snapshot only**. Current RiftOS and separate Riftos-builder continue development/pushes on their existing `main` branches, never on the backup. Current source integrates APK-owned `RiftComponentReleaseLedger` (protected pending/last-known-good/quarantined metadata, immutable SHA checks), `RiftCoreRecoverySupervisorService` (best-effort, nonexported independent-process Core liveness observations with durable journal, **not** automatic restart guarantee), `RiftShellGraphicalComponentV1` + `RiftShellPlatformServicesV1` (real graphical presentation seam and protected Core IPC for RAPP surfaces/events), and `RiftShellCandidateSwitch` (bounded classloader+SHA+ABI/qualification checks, selected only before embedded desktop construction, interrupted startup returns embedded). `RiftShellActivity` uses external presenter only if independently qualified; by default embedded desktop remains intact. Separate Core/Shell revision metadata is reported in read-only Core status. Kotlin Gradle exact source snapshot, manifest, RiftOS wiring checks, Builder gates and DEX ABI markers coordinated. This is NOT independently compiled real Core or graphical Shell, trusted qualification/activation workflow, verified N-1 automatic rollback or H3/device proof. Continue full build/integration work on `main`; user still manually dispatches signed APK builds and actual device tests after integrated gates are ready.

## 2026-10-10 — H Crash Evidence instrumentation source checkpoint (NOT H3)

New protected APK-owned `RiftCoreRecoveryDiagnostics.kt` stores bounded app-private Android API30+ historical process exit information, optional available tombstone/log trace digest/excerpt, caught verifier/startup exceptions, and fatal Java stack information while delegating to the previous Android uncaught-exception handler. Invoked from `RiftBootstrapHost.startCore`, installed before external Core initialization, visible as a bounded read-only Core status summary. Gradle/Builder/script source gates now include the 102nd Kotlin source. **This does not finish all H in one patch**: independent executable Core distribution, trusted native qualification/activation, separate Android survivor/watchdog and known-good external N-1 rollback still need actual implementation and physical-device proof. A supposed “one last APK build” or no-repack workflow cannot be asserted yet; test embedded-default stability first. No signed user APK build or external Core activation performed during development.

## 2026-10-10 — Signed E1 selector E0-compatible device checkpoint #697 PASSED (limited scope)

Signed user-manual Android APK **#697**, run `38088702334`, source `812dd80d1e0e1b55b4c199fcb211cf08b23afbe0`, installed/live. E1 guarded startup selector reports **embedded** selected, V1 ABI, external Core/Shell OFF, no Core activation pointer/boot marker, no fallback event, automatic promotion and in-process hot swap OFF. Independently confirmed real Core PID2826 and graphical Shell PID3076, three installed RAPPs, retained external module proof, zero providers/grants/pending registry journal. Opened existing `rift-module-builder` RAPP: Core reports one running attached session generation1 and 15-node surface revision2, unchanged PIDs, Shell connected. **PASS: embedded-default E1 switch and basic graphical RAPP backward compatibility only.** Not tested: independently compiled/qualified external Core, trust-scoped activation, actual crash recovery, previous external Core N-1 rollback or independent graphical Shell. H–C–R–S–F [roadmap](docs/systems/android-host/EXTERNAL_CORE_SHELL_ROADMAP.md) remains the active plan; no gate skipped.

## 2026-10-10 — CURRENT AUTHORITY: external Core → N-1 recovery → graphical Shell → minimal host APK

**Read first:** [Full execution and device-acceptance roadmap](docs/systems/android-host/EXTERNAL_CORE_SHELL_ROADMAP.md). The user's priority is to complete one stable Android bootstrap/supervisor that supports independently built Core and Shell revisions, not to keep repacking RiftOS for ordinary compatible updates. The sequence is **H0–H3 host capability/loader/approval/watchdog/device proof → C1–C4 complete independent external Core and hardware proof with embedded execution OFF → R1–R4 stable external N-1 update rollback, durable bounded crash evidence and real process-death recovery → S1–S3 external real graphical Shell and independent recovery → F1–F3 reusable services, remove embedded implementations and final minimal APK/device regression**.

**Conditional build rule:** The pushed E1 bootstrap selector `812dd80d1e0e` is source only until user-manual signed Builder and installed device testing; the last proven installed APK is **#695** (source `c92d8b72319a`), E0 embedded-ABI compatibility only. Critical host infrastructure (external installer/qualification, narrow callback ABI, watchdog and last-known-good recovery) is **not yet complete**; Android host/ABI deficiencies can still require additional APK builds. After H3 device acceptance, ordinary compatible Core/Shell updates should use **separate external builds without APK repacking**. Embedded Core must not run to prop up external Core during C3/C4 proof, but remains recoverable until the migration fully passes. Recover **previous proven external N-1** before the embedded fallback; after embedded removal N-1 is the sole recovery path. A native full dump may be unavailable; collect all accessible crash evidence and report gaps truthfully. No in-process hot swap, second Core authority, unsanctioned repo push or automated signed Builder.

This section supersedes older E0–E5 source-only/status language as **future priorities**, not as retroactive evidence of gate completion. Python/Node/general runtime-provider enrollment stays deferred until F3/E5 accepted.

## 2026-10-10 — E1 fail-safe external Core switch SOURCE checkpoint (LOCAL, NOT BUILT)

User priority: add one stable-host startup switch, so later **real independent Core** revisions can be staged, tested and rolled back without routinely repacking the Android APK. Added a host-owned optional `RiftCoreCandidateSwitch` (fixed content-addressed DEX SHA+entrypoint/ABI/qualification checks, APK-class duplication rejection, fail-closed unaccepted boot marker and next-start embedded rollback), routed via `RiftHostCoreComponents.initializeAtBoot`. Prevented ContentProvider from initializing embedded Core before main Application selection. **No Core activation writer, verified qualified DEX, complete portable closure or hardware acceptance yet**. Embedded remains the only live working default; generic `probe` loader, native graphical Shell and MCP/relay remain unchanged. Full E1 extraction/qualification/trusted consent and device crash+restart proof are mandatory before an external Core can actually be promoted. No in-process hot swap, auto build, app install or source push.

## 2026-10-10 — E0 signed-device checkpoint accepted; E1-A IN PROGRESS (local source)

User signed and installed **RiftOS #695** run `38081795744` exact source `c92d8b72319aa9c2915f94cf687f0a3031794795`. MCP independently verified the new `riftos.host.core-component/1` ABI1 selector is **embedded**, external Core/Shell OFF, separate Core 19450 / Shell 19421, all 3 installed RAPPs retained, no pending journals, and live existing `rift-module-builder` Core app attached generation1 / UI revision2 with 15 nodes. **E0 embedded compatibility DEVICE PASS**; this is not proof of all nine ABI verbs or crash tests.

**E1 started, not accepted:** add audited transitive real-Core class dependency scanner and `E1_CORE_CANDIDATE.md` ownership/ABI/build-and-proof contract. Next E1-B extract genuine implementation dependencies and replace broad Android Context with narrow host callbacks; E1-C reproducible independently compiled DEX, strict forbidden/duplicate/ABI/SHA verification; E1-D disposable inactive load/proof, leaving production Core embedded. E2 is the first potential guarded real Core activation gate. Script only, docs only; Android APK code, external activation, Builder user-trigger unchanged. Source push remains explicit.

## 2026-10-10 — E0 IMPLEMENTATION: Core V1 host adapter wired (LOCAL UNBUILT/UNPUSHED)

After #691 Gate 2-C device acceptance, E0 work now **actually changed Core source**: `RiftHostComponentAbiV1.kt` defines host-owned `RiftCoreComponentV1` and future `RiftShellPresentationV1`; a single non-selectable `EmbeddedCoreComponentV1` delegates to existing production Core. `RiftBootstrapHost` initializes Core via this boundary; `RiftCoreSurfaceIpcProvider` delegates RAPP snapshot/catalogue/open/reattach/stop/focus/events/install/uninstall after its existing OS-caller and payload validation. Read-only Core status reports ABI 1, selected embedded, external Core/Shell disabled. Exactly the same Android components and Core authority remain live. Added exact Gradle source allowlist, documentation ownership, and strict source guards. Source-contract audit 19/19 and Kotlin lexical brace checks passed, **not Android compiler/device tests**. Builder preflight/selftest was updated in the separate local Builder repo to recognize the new valid route; no Builder workflow, signing or trigger change. Both repos are DIRTY LOCAL, no commits/push/build. E0 still needs compiled parity and a genuinely independent Core class/build closure; E1 is next after E0 readiness. Python/Node deferred.


## 2026-10-10 — ACTIVE PRIORITY: Gate 2-C acceptance complete; externalize actual Core/Shell implementation next

Signed user-installed RiftOS **#691** from `b352bec485073e1f9071c5398e5ed829407213ff` device-passed the bounded real-journal `module.recovery.proof` with one-use exact approval and preserved active module/proof nonce, unchanged Core/Shell PIDs, 3 RAPPs, zero providers/grants/tickets, no pending startup. The **direct Core consumed-bearer replay** passed on signed #690. Thus Gate 2-C's direct authorization/recovery device acceptance is complete; actual interrupted power-loss/cold boot and private failed-stage directory cleanup still have distinct evidence requirements.

**User priority:** Resume the existing `docs/systems/android-host/BOOTSTRAP_HOST_MIGRATION.md` plan. The Core and real RiftShell processes are already separate. **Next work is extracting their actual implementation classes and suitable native/services out of the APK**, behind versioned host-owned Android adapters, independently sealed components and safe startup/rollback. Current `RiftBootstrapHost` only loads external `probe`; `RiftCoreSurfaceIpcProvider` still calls embedded Core, and `RiftShellActivity` constructs the embedded desktop. Do not remove or bypass this fail-safe branch until host/portable authority and independent build ownership have been separated and proven.

**Execution gates:** E0 host/portable source & ABI cut → E1 independently compiled inactive real Core → E2 safe external Core activation/rollback and device parity → E3 independent real graphical Shell → E4 native/OS services → E5 minimal-APK/persistence/recovery final device acceptance. User alone dispatches signed APK Builder; no direct GitHub repo edits, all pushes explicit. **Defer Python, Node.js, npm, general installer and signer-pinned runtime-provider enrollment (C2-B2) until E5 is accepted.** Earlier dated entries below remain historical checkpoints and must not override this priority.


## 2026-10-09 — First external DEX proof: trusted native staged/activate SOURCE candidate

Native SAF picker in Admin Approvals + Core signer/PID/one-use scoped `bootstrap.probe.stage` and SHA-bound `bootstrap.probe.activate` are implemented in source. DEX bytes arrive as read-only descriptor, stored immutable/hash-addressed in Core storage; separate one-use approval activates only `:riftBootstrapProbe`, never Core/Shell. Manually triggered external probe artifact workflow compiles raw DEX outside RiftOS APK. Next user-manual signed APK build/device proof MUST test Core/Shell/RAPP parity, stage, second approval, isolated process proof, malformed DEX, replay/denial/expiry and previous-version rollback. Then design generic installer and actual Core/Shell extraction. **New gate is source-only/unpushed, not compiled or device accepted**. C2-B2 real runtime enrollment still separately on HOLD.

## 2026-10-09 — Bootstrap Host: staged DEX revisions / proof-only rollout

After installed #680 and physically passing the **positive** C2-A create/rollback path (negative cases remain), the next coordinated bootstrap source gate introduces `RiftBootstrapComponentStore`: bounded SHA-256 read-only stage, Android AtomicFile activation pointer, previous-revision rollback on interrupted module startup, and a non-exported, inert `:riftBootstrapProbe` Android service for independent crash containment. Critical external Core/Shell activation is hard-disabled while their implementations remain APK-owned. **Not yet finished:** trusted Core-authorized native module importer/consent UI, external proof DEX, real module install/activation/recovery on device, portable Core extraction, shell extraction, independent processes and C++ service ABI. Source-only, no new signed device proof. Continue user-manual Builder at milestone/Android-risk gate; do not resume C2-B2 without separate approval/test scope.

## 2026-10-09 — Minimal APK Bootstrap Host migration (new architectural workstream; SOURCE checkpoint only)

User authorized a coordinated migration to a tiny stable APK host with independently replaceable RiftOS Core, Shell, UI, services and runtimes. Clean source `3c8a7bd5f6f5` was archived before changes. First source checkpoint adds `RiftBootstrapHost`: existing default Core/Shell startup delegates through the host and retains full embedded fallback; optional hash-verified DEX entrypoint is selected at process startup only. Core status exposes passive host diagnostics. All actual Kotlin/C++ Core/Shell implementation still ships in the APK. See `docs/systems/android-host/BOOTSTRAP_HOST_MIGRATION.md` for required gates: compile/device-prove embedded parity, installer/atomic activation/recovery, external Core, external Shell, process-safe C++ services, final signed regression. Do **not** count this as full migration, live-editing, Kotlin compilation, signed APK or device pass. Keep C1.4-C2-A signed-device proof and C2-B2 HOLD independent; user alone initiates the existing Builder.

## 2026-10-09 — C1.4-C2-A physical #678 errno 13 hardlink failure: exclusive-create proof patch SOURCE

User-signed and installed RiftOS #678 (Builder run 38007385307, source `272f39b2ec11`). Screenshot and live MCP `core status` independently show the *exact* C2-A failure: `atomic-create-only-publish`, `ErrnoException`, **errno 13 / EACCES** after one-use native approval consumed. This proves both the Java and Android native **hard-link** paths failed at publication; not a signer, ticket, or scratch-write problem. The Core registry, scratch and journal were all absent afterward; grants stayed at zero, Core and Shell separate and both intentionally retained RAPPs present. The old zero-candidate C2-B1 read-only discovery had already passed. **C2-A remains NOT device passed.**

Patch changes **only the C2-A temporary EMPTY Core registry proof** from hard-link publication to kernel-enforced `android.system.Os.open` with `O_CREAT|O_EXCL|O_NOFOLLOW|O_WRONLY` and private `0600` permissions, writing/fsyncing through the same exclusive descriptor. Unlike link/rename, this is NOT pathname-atomic during the write. To keep *Core-observed* registry transactions indivisible, both `RiftExternalRuntimeProviders.providers()` and `status()` now lock through Core's shared `withSafeRegistryRead` until the proof completes and reject reads if a persisted recovery journal is pending. Recovery and synchronous rollback may delete exact own partial prefix bytes ONLY if the journal's scratch marker is verified or the scratch itself is the authorized partial artifact. They still never overwrite existing registries, never unlink unknown bytes/symlinks, and never grant RAPPs admin/runtime registration. Any unknown state fails closed. This is temporary proof-specific and **must not be mistaken for production C2-B2 cross-process atomic provider replacement**.

New physical proof is required: user-only signed Builder on this source, install RiftOS, keep its real Shell foreground for request/Allow Once/execute within 45 sec. Require success text, Core audit consumed→rolled-back, `pendingJournal=false`, `registryExists=false`, `temporaryRegistryExists=false`, `lastFailureStage=none`, zero providers/grants, original two RAPPs and separate Core+Shell. On any failure, read stage/type/errno via `core status.adminRegistryProof` and stop. Then denial/cancel/replay/expiration/revocation/relaunch and read-only discovery. Hold C2-B2 until signed device pass.



## 2026-10-09 — C1.4-C2-A Builder run 38006658730 failed obsolete UI marker; fixed in source/Builder contracts

USER manual run `38006658730` on RiftOS `b87820dba3e7` passed setup and Builder selftests, then **failed Builder C2-A preflight before Kotlin compilation**. The Builder expected the literal `FAILED at $stage ($kind)`; the current native failure UI correctly uses `$type` to include optional Android errno and an explicit `No success claimed` message. Synchronized Builder preflight/selftest and RiftOS wiring validator to assert the meaningful failure/errno/no-success safeguards **without pinning a local variable name**. This patch does NOT alter runtime behavior, admission scope, or signing; source-only 7/7 targeted contract checks and project audits passed. **Next USER action: manually rerun signed Builder and install if green; C2-A signed device test and actual registry rollback still pending. C2-B2 remains HOLD.**


## 2026-10-09 — C1.4-C2-A signed #676 device blocker: fail-closed Android link compatibility + bounded root-cause diagnostics SOURCE PATCH

Signed USER Builder #676/source `30bebf009e88` successfully booted Core and remote Shell. The two missing RAPPs were deliberately **uninstalled by the user**; the remaining `riftbuild-hosted` and `riftpp-compiler-lab` are expected. C2-B1 read-only installed-runtime discovery returned **zero candidates/zero registrations** correctly, but C2-A's exact native-approved one-use `runtime.register` ticket was consumed twice and **FAILED** (`registry-proof-rejected`); no runtime registry or pending journal remained. The protected Core IPC originally hid the underlying exception as `Core IPC response JSON missing`. The true failing filesystem stage is **not yet known** from that build.

C2-A correction staged (not yet compiled/device-proven): retain atomic create-only `Files.createLink` and allow fallback to Android `Os.link` only when the Java NIO API is unsupported or reports a filesystem exception. Both use kernel-enforced hard-link *no-replace* semantics; never use rename overwrite or unjournalled publication. Instrument original transaction stage across `finally` cleanup, exception type and non-sensitive numeric Android errno. Read-only Core `adminRegistryProof` status conservatively indicates unknown paths as PRESENT and returns `lastFailureStage/Type/Errno`; if transaction fails **and** no live registry, scratch file, or pending journal exists, Core returns a dedicated schema-correct `transactionCommitted:false` failure result so the native UI can display the stage. Otherwise exceptions still fail closed and require recovery/manual inspection. One-use approval, signer, Core/Shell PID/lease, installed-app permission and scope restrictions remain unchanged.

**Next user-only signed checkpoint:** manually build/install the corrected RiftOS, keep the actual production Shell foreground while granting/executing the 45-second scoped test. Required proof: `transactionCommitted=true`, `rolledBack=true`, `registryRestored=true`, zero registrations, no registry/scratch/journal; deny/cancel/replay and Core/Shell/two retained RAPPs unchanged. If it still fails, capture `core status.adminRegistryProof.lastFailureStage/Type/Errno` and native failure text; do not promote C2-A until actual signed-device positive proof. **Hold C2-B2 until this gate passes.** Our broader C2/C3/C1.5 major-milestone policy remains in place but does not override this expressly requested risk gate.



## 2026-10-09 — LOCKED: major-milestone device validation; C1.4-C2-B1 READ-ONLY source checkpoint

**User-directed cadence:** no forced user manual signed APK installs for every small C1.4/C1.5 patch. Run focused source/security/regression and ownership checks each patch; group integration; perform USER-triggered comprehensive signed Android tests at finished **C1.4-C2**, **C1.4-C3**, and **C1.5**, with an earlier device test **only** where Android-specific Binder, process, real provider binding/installation, privileged filesystem or rollback safety requires proof. All SOURCE/BUILD/DEVICE labels stay distinct. Only user dispatches Builder and installs signed RiftOS.

**C1.4-C2-B1 staged SOURCE ONLY:** add read-only Android PackageManager discovery of independently installed generic runtime services advertising `com.riftos.runtime.EXECUTE_V1`. Each candidate must be exported/enabled, declare `riftos.runtime.provider.id` and `riftos.runtime.executor.kind` metadata, have a supported current execution kind, one installed Android signer, a verified fully qualified package/service and a pinned future admission target derived from identity+signer. Only authenticated production RiftShell may request the list via native Admin Approvals `discover-providers`. This checkpoint NEVER registers/enables/binds/executes a runtime or mutates `registry.json`; `enrollmentEnabled:false, registryModified:false` are explicit. **C2-B2 must implement independently authorized real enrollment and safe rollback/restore** before C2 full signed-device acceptance. C1.4-C2-A empty-registry transaction is still source only, not hardware-proven. Latest independently device-proven milestone remains C1.4-C1 (#669).



## 2026-10-09 — C1.4-C1 fully DEVICE PASS; C1.4-C2-A Core registry rollback SOURCE CANDIDATE

**C1.4-C1 fully signed/device-proven on USER-built Builder #669.** Core real fixed temporary C: write/verify/rollback, Deny/Cancel, ticket replay, WRITE-scope expiry, native window-close revocation and a USER-approved actual production `:riftShell` PID 24499→1647 replacement all passed. Core PID24544 survived; the unused WRITE ticket was audited `revoked / shell-replaced`; temporary typed probe was cleaned and the original four protected RAPPs remained. No Core-root or blanket administrator access.

**C1.4-C2-A is now SOURCE ONLY; not signed/compiled or device-proven.** The first runtime-management proof deliberately does not register a provider. It adds an exact-scope native `runtime.register` consent for `core://runtime-providers/registry.json#empty-c2a`. After installed-APK-signer attestation and one-use PID/TTL-bound Core ticket consumption, Core may temporarily create a valid **EMPTY** `riftos-runtime-providers/1` registry (`providers: []`) in the previously absent private path, durably journal first, fsync/publish/verify, then obligatorily remove it and restore the initially absent registry/directory. Existing nonempty or even empty registry files are NEVER overwritten, no provider is admitted or activated, and a Core startup recovery only removes exact journalled proof bytes. The ordinary provider resolver retains signer validation and its original external-runtime fallback. All new source/Builder/signed-APK contracts are gated; USER alone dispatches signed Builder. Positive and negative Android proof, crash recovery and four RAPP preservation MUST be checked before promoting C2-A.

**C2-B onward** must actually admit Android installed provider components with trusted signer pinning, exact user-approved service/executor identity, atomic replace/restore and negative tests, then C2/C3 system installer / scoped operations. No assumption that this empty-registry proof alone implements provider registration. C1.5 extracts embedded language engines only AFTER provider admission/rollback proves device-safe.



## 2026-10-09 — C1.4-C1 #668 installed; response validation correction awaits device build

Signed Builder #668, manually dispatched and installed, proved Core/Shell processes, four RAPPs and clean C1 status. A deep IPC review uncovered a response-schema mismatch for the single real execute-rollback-proof action, now corrected solely in Shell response validation and guarded by RiftOS/Builder contracts. Original consent actions remain unchanged. Next user-only Builder/install should prove the new APK, then test exact temporary C: write/rollback, ticket replay/deny/revoke/expiry, native close revocation and no lingering journal. Do not promote C1 until hardware proof. C2/C3 and C1.5 remain future gates.

## 2026-10-09 — C1.4-C1 exact system canary write + immediate rollback SOURCE CANDIDATE; manual device proof pending

C1.3-C/D/E and C1.4-A/B consent-only are independently DEVICE PASS; last installed user-manual signed Builder **#667**, executable source `bba25e69`. **C1.4-C is staged**, NOT a blanket administrator unlock. **C1.4-C1 source only** first proves a REAL but harmless scoped Core-owned virtual C: file write, fsync, verify and mandatory rollback in a single operation. Only exact `system.fs.write` target `/C:/RiftOS/.c14c-rollback.txt` is ever accepted; no caller-supplied filesystem path/content. Core persists a transaction journal before writing, removes the canary and clears journal before returning success, and reconciles interruptions on next Core process startup. A new privileged-effect Binder path requires authenticated production `:riftShell` PID/signer/UID, fresh approved exact operation+target 45s ticket, foreground Core lease and single-use ticket consumption; the old B harmless `system.fs.read` proof remains separate. Core audits consumed/rolled-back/failed metadata and read-only `core status.adminRollbackProof` reports canary/journal state. UI explicitly selects rollback scope and displays a native approval dialog explaining the temporary write, plus one-use execute button. Closing Admin Approvals requests revocation of ALL unused tickets bound to the shell PID rather than depending on the current visible bearer. Core general admin elevation and grants remain DISABLED. This is RiftOS virtual C: **NOT Android root or a direct Linux filesystem write**.

**C1.4-C1 NOT COMPILED/SIGNED/DEVICE PROVEN:** user alone builds next. Required negative+positive device proof: test both previous B mode and new C1 canary; native dialog shows exact write target, Deny/Cancel/Revoke/expiry refuse effect, approved + foreground one-use action performs write/verify/rollback returning no residual file/journal, replay/scope mismatch denied, app/sessions/grants stable, four originals intact; Core restart interruption recovery and close-with-active-approved ticket need separate device proof. Real `:riftShell` death/PID-change revocation requires explicit user authorization to intentionally terminate process. **Do not promote C1** until actual rollback and guards are signed-device-proven.

**C1.4-C2/C3 FUTURE:** separately authorize signer-pinned runtime registry updates (atomic replacement/restore), verified user-approved software installation with safe rollback, scoped system filesystem read/write beyond canary and guarded protected-process policy. Each has independent Core tickets, exact targets and device proofs; no protected kernel/shell termination or package mutation included in C1. After completed C gates, C1.5 extracts embedded QuickJS and other language engines into installable runtime providers. Python/Node discussion remains deferred.


## 2026-10-09 — C1.4-B consent-only DEVICE PASS on user-signed Builder #667; C1.4-C next

C1.3-C/D/E and C1.4-A remain device-proven, and **C1.4-B** now has Android signed-device evidence on user-manual Builder **#667** (run `37944426879`, executable RiftOS `bba25e699fa53c08d681b99cc41710e28c0de110`). Core PID24051, production graphical shell registered PID24167. Core status: exact signer/PID-bound ephemeral consent protocol, max8 tickets, 45-second TTL, no persisted grants, system effects disabled, admin elevation disabled, `grantCount:0`. Real native `Admin Approvals` window and Android native scope-displaying dialog confirmed. Request→Allow once→Consume succeeded without effect; replay denied; Deny and Cancel left no usable ticket; approved ticket explicit Revoke worked; 45-second expiration cleared approved ticket and rejected consumption. Audit returned requested/approved/denied/consumed/revoked/expired metadata without bearer, signer or target. No RAPP sessions/surfaces/leaked Core focus, four production RAPPs intact.

**C1.4-B DEVICE PASS for tested consent-only flows**, not actual admin privileges or every process-lifecycle hardening path. Two additional cases remain **unverified on-device**: automatic revocation of unused approved tickets after native window close; and old shell PID approval revocation during real graphical process replacement (no new shell kill authorized/performed). A follow-up UI attempt hit Android System UI foreground stabilization, but Core returned zero tickets/grants. Retest hardening before relying on real elevated C effects.

**C1.4-C NEXT — NOT IMPLEMENTED:** attach each real protected operation (system filesystem, user-approved software installation, signer-pinned runtime registration and guarded protected-process policy) to a newly approved operation-specific trusted Core authority; strict signer, target, TTL, single-use and revocation; transactional rollback and per-operation signed-device negative/positive proofs. No OS-root bypass or blanket administrator permission. User alone manually triggers Builder. **C1.5 later** externalizes embedded QuickJS/language engines into installable runtime providers after proving provider replacement and package lifecycle; Python/Node design discussion deferred by user.


## 2026-10-09 — C1.4-B trusted administrator consent SOURCE CANDIDATE; manually signed device proof pending

**C1.3-C/D/E and C1.4-A are DEVICE PASS** (latest installed Builder **#664**, executable `f3dd5be5`, a documentation-only #663 A promotion). **C1.4-B source** introduces real Core-owned `RiftCoreAdminConsent` and real graphical RiftShell **Admin Approvals** system window. OS authenticates the production graphical Binder PID/UID/process name and installed RiftOS signing certificate on every ticket action. Core creates at most 8 random 128-bit in-memory tokens, each bound to the actual caller PID, signer, exact single `system.fs.read` test operation and `/C:/System` test target, with 45-second expiry. Core audits bounded metadata-only request, approve, deny/cancel, expire, revoke, consume decisions, no bearer secret/target path persisted. The native window requires an actual foreground user click, displays exact operation and target in a trusted Android AlertDialog, offers **Allow once / Deny / Cancel**, revoke, and consume-proof. A consumption test invalidates the token atomically; replay and scope substitution are denied; normal RiftShell PID change revokes old tickets. Core status surfaces only aggregate counts and `systemEffectsEnabled:false`.

**C1.4-B deliberately DOES NOT authorize any privileged side effect:** the one-use token only proves approval/consumption. Real system filesystem access, install, runtime registration and protected process changes stay C1.4-A denied; no root/bypass or general RAPP admin grants. Only trusted native UI can initiate; no native-terminal/RAPP admin grant command. **C1.4-B is SOURCE ONLY** until an independently user-manually built signed APK and device testing. Physical acceptance: authentic graphical Admin Approvals window, consent Allow once, denied replay, Deny, Cancel, expiry after 45 seconds, revoke and Core audit, foreground check, effect disabled, original four RAPPs unchanged, no Core session leak. Core shell recovery remains working. **C1.4-C later** implements narrowly scoped actual operation authorization and rollback with independent approval/device proof. C1.5 subsequent QuickJS/internal language-engine removal after runtime providers prove real apps.


## 2026-10-09 — C1.4-A DEVICE PASS (#663); C1.4-B next independent source/device gate

C1.3-C/D/E remain DEVICE PASS (#655/#660/#661). **C1.4-A DEVICE PASS** on user-manual signed Builder **#663** run `37918414463`, installed executable source `74f2688ebe939682c36a4e0e08be62b4df505cfa`: Core PID29992 separate real shell30093; `systemCapabilities` Core policy default deny/admin elevation disabled/grants0; native `permissions policy` and `permissions audit` read-only. Native `kill kernel` was denied with audited `process.protected.kill`. Isolated disposable `c14a-admin-denial-probe-20261009` received ordinary `fs.write` Allow yet its explicit out-of-scope `/C:/System/c14a-policy-negative-denied.txt` request was denied and audited `system.fs.write`, with file absent before/after. Core session/window/queues zero after close, uninstall cleaned package and revoked ordinary grant, exact four original RAPPs intact.

**C1.4-B NEXT — NOT STARTED:** design verified user-initiated, single-use Core-owned elevated consent tickets bound to exact caller/package signer, operation, target and expiry; trustworthy foreground UI, deny/cancel/timeout and explicit revocation, auditable. Do NOT let ordinary RAPP capabilities or `fs.write` Allow become system admin automatically; do not bypass Android restrictions. Build/sign/install and real negative/positive consent lifecycle proofs as a separate user-manual gate.

**C1.4-C LATER — NOT STARTED:** wire actual narrowly scoped system files, software install, signer-pinned runtime registration and protected process actions behind B policy with atomic rollback. A's category declarations alone do NOT prove those operations. C1.5 subsequent embedded-engine retirement. All manual Builder workflow/user protected apps unchanged.


## Active 2026-10-09 — C1.4 system capability/admin elevation; A SOURCE CANDIDATE, B/C not started

C1.3-C/D/E are **independently DEVICE PASS** (signed user-built #655/#660/#661). **C1.4 is Core policy authorization for RiftOS operations, NOT Android root or a bypass of Android OS permission/security controls.** Do not issue blanket system-write capability grants, grant Android privileges by claiming they exist, or let a RAPP manifest, shell/window UI or same-UID process become administrator.

- **C1.4-A — Core system policy/denial audit (SOURCE ONLY):** Mandatory `RiftCoreSystemCapabilities` in Core declares precise `system.fs.read`, `system.fs.write`, `software.install`, `runtime.register`, `process.protected.kill` classes. ALL remain disabled, deny-by-default with no grant API. Durable bounded (64 entries, 24 KiB) Core-only metadata audit, no raw paths or secrets. Reject RAPP filesystem boundary escapes and protected process termination as before, but now record denied attempts. Expose read-only `core status.systemCapabilities`, `permissions policy`, `permissions audit`; preserve existing app-local grants/consent and Core package management. User-manual Builder+physical device negative tests required to promote A: ordinary trusted existing operations still work, protected paths/process kills still DENIED, audit reflects each denial, no elevated grants, original four RAPPs untouched. **Not yet built/tested**.
- **C1.4-B — Explicit trusted administrator consent/revocation (FUTURE):** Core-owned single-use short-lived scope- and identity-bound tickets, foreground trustworthy confirmation UI, auditable accept/deny/cancel/timeout, no shell-supplied grant identity, package update/removal and policy change revocation, no durable unbounded elevation; device prove all paths fail closed.
- **C1.4-C — Operation-specific elevation and rollback (FUTURE):** Wire system files, user-approved software installation, runtime registration and guarded protected-process policy to Core authorization checks; keep Android platform permissions enforced, require verified signer/target/path and transactional rollback for installs/registrations. Negative and positive real signed-APK device proof per operation; never elevate a normal RAPP's `fs.write` to arbitrary system writes.

C1.5 embedded language-engine retirement and C0.3 remain later. Manual Builder dispatch/installation remains user-owned. Latest installed executable is signed #661 C1.3-E; this C1.4-A source has NOT been built or installed.


## 2026-10-09 — C1.3-C, D and E each DEVICE PASS

**C1.3-C (#655), C1.3-D (#660), C1.3-E (#661) have independently passed their user-manual signed-build Android device gates.** E physical evidence: default Core PID10730 and running disposable Core RAPP gen1 survived the real production shell PID10831 being killed; Core automatically started the new real shell PID14251, restored desktop/taskbar and disposable RAPP window with the `C13E_PRECRASH_661` content without a manual RiftOS launch, and new ACTION count2/TEXT_INPUT `C13E_POSTRECOVERY_661` succeeded through generation-safe cross-process Binder. Clean remote Close and uninstall returned Core apps/sessions/surfaces/queued/focus zero and preserved four original production RAPPs. Normal background did not falsely trigger restart. User-manual Builder #661/run `37903041688`, executable source `fc48683144050c07f878e10a29bfcb8c83f7be8d`. This concludes the C1.3 separate-process Core/shell ownership and real graphical process recovery gates on the tested device. Future C1.4/C1.5 and other development remain separate; do not infer arbitrary Android background policy or complete browser history restoration from this proof.


## Active 2026-10-09 — C1.3-E real production graphical shell death/restart SOURCE CANDIDATE (not device proven)

C1.3-C (#655) and C1.3-D (#660) are separately **DEVICE PASS**. E adds Core-owned bounded `:riftShell` heartbeat/process watcher, automatic relaunch attempt, authenticated old window snapshot claim, safe-zone geometry/z/min/max/focus reconstruction, Core exact-attachment-generation no-BOOT RAPP reattach, and guarded disposable-only live graphical process-kill QA. Native shell remains a separate Android process with Core RAPP execution/state in default Core. **Android background Activity launch can be restricted**; actual signed-device automatic recovery must be proved rather than assumed. E must receive its own user-run manual Builder and live PID/generation/window/GUI/input/cleanup evidence; do not promote based on source checks or manual relaunch. Preserve all FOUR installed production RAPPs. C1.4 and C1.5 later. D remains promoted independent of any E failure.


## 2026-10-09 — C1.3-D DEVICE PASS; C1.3-E NEXT, independently gated

**C1.3-C signed Builder #655 DEVICE PASS. C1.3-D signed Builder #660 DEVICE PASS.** Actual Android default launcher desktop, taskbar, native window manager and graphical RAPP renderer now run in real separate `:riftShell` process, distinct Core PID25396 and shell PID25436 (user-built #660 executable source `d43a30e`). Device-proven remote Core package/UI IPC, Core-only app BOOT gen1 → remote-shell rendering/input ACTION+TEXT_INPUT without duplicate BOOT, generation-scoped GUI close/cleanup, native Files and RiftBrowser WebView, and final user manual native RiftShell terminal `ps` output showing kernel/desktop/shell/focused terminal. Four protected RAPPs untouched. No new build needed for D.

**C1.3-E NEXT — NOT STARTED.** It must separately implement and device-prove actual production graphical-shell process crash/kill and restart with Core PID and Core-owned running RAPP generation/state preserved, and automatic desktop/window reconstruction. Do NOT conflate stopping a graphical Activity or the read-only remote probe with real production-shell death. Update source/Builder/docs and user-manually build/sign/install for E, then live-device proof. C1.4/1.5 later as documented.


## Active 2026-10-09 — C1.3-D implementation underway (SOURCE ONLY, no signed device pass)

C1.3-C signed Builder #655 is DEVICE PASS. **C1.3-D now migrates the default desktop/window manager and graphical RAPP client to a real independent `:riftShell` process**, connected to Core-owned execution via authenticated versioned bounded Binder IPC and distinct Core/shell PIDs. The new source also bridges Core-issued UI permissions, native terminal/installed packages, desktop window state and Core-first app launches while keeping the read-only `:riftShellProbe` separate. Update both RiftOS and Builder tests/docs together; follow normal user-manual Builder and real Android device gate. Do not infer success from sources alone. **C1.3-E NOT STARTED**: after D device PASS, separately prove killing/restarting the *actual* shell process leaves Core/RAPP alive and restores desktop/windows automatically. C1.4 capability permission elevation and C1.5 QuickJS retirement remain later.


## 2026-10-09 — C1.3-C DEVICE PASS; C1.3-D NEXT as separate gate

User-manual signed Builder #655 installed source `8d7608f`. On-device disposable Core-only RAPP gen1 survived real `MainActivity.recreate()`, preserved PID7528/session/surface, reattached GUI and accepted manual ACTION/TEXT_INPUT `C13C_MANUAL_655`; Core terminal view confirmed committed text and advancing surface revision. Focus settled null in background, Core stop/uninstall fully cleaned test app and original three RAPPs remained. **C1.3-C device-proven**. **C1.3-D not started:** migrate actual desktop, taskbar, graphical windows/input and application surface rendering into its own real `:riftShell` process, while Core/RAPP execution remains independent; add versioned bounded authenticated Core↔shell transport; do not equate the existing remote diagnostic probe with real RiftShell. Build and device prove D separately, then C1.3-E real RiftShell process death/automatic restart + Core/RAPP survival proof as another separate gate. Manual Android Builder remains USER ONLY.


## Active 2026-10-08 checkpoint: C1.3-C → C1.3-D → C1.3-E (separate device gates)

The user's newer instruction **replaces** the older proposal to patch C/D/E in a single pass. Proceed in strict order, requiring complete RiftOS+Builder source/test/docs synchronization, user-manual Builder, signed APK installation and separate installed-device proof for each gate. Preserve the pre-change RiftOS and Builder backup archives. Do not silently advance after a source-only candidate.

- **C1.3-C — SOURCE CANDIDATE; device pending:** Core owns both GUI and shell-less RAPP BOOT, FIFO input dispatch, effect continuation, generation/focus authorization, and published surfaces. MainActivity and RiftRappHost must not own execution. Test Core-only launch, GUI reattach without duplicate BOOT, input/state, Activity destroy/recreate, explicit stop, cleanup and original installed RAPP preservation.
- **C1.3-D — NOT STARTED:** migrate the *actual* desktop, taskbar/window manager and RAPP graphical presentation to a separate process with authenticated/bounded Core IPC. Do not count the read-only `:riftShellProbe` as production RiftShell.
- **C1.3-E — NOT STARTED:** only after D device proof, terminate/restart the real RiftShell and prove Core PID/RAPP generation/surfaces survive while the desktop automatically reconstructs.

C1.4 authorization/elevation, C1.5 language-engine retirement and C0.3 cleanup are future work. Normal RiftOS Builder remains user-dispatched only.


This roadmap describes intended work, not shipped capability. Current implementation status is tracked in `docs/PROJECT_STATUS.md`.

## Now: native MCP connection + browser stability

- Keep `main` authoritative and use `android-apk` for staged validation when useful.
- Keep MCP execution local while adding an authenticated outbound WSS transport to a public MCP relay.
- Preserve the ChatGPT Web compatibility adapter until native plugin calls pass end-to-end testing.
- Keep `RiftToolHost` as the single capability authority for MCP permissions, audit and tool dispatch.
- Preserve the optimized ChatGPT compatibility path: mutation-scoped processing, compact one-shot tool context and serialized tool calls.
- Keep removed Rift AI cockpit/session/journal code out of the active runtime; AI-assisted development should use the MCP tool surface rather than a second hidden task controller.
- Harden RiftBrowser move/resize/focus and long-chat behavior across phones, tablets and DeX.
- Implement the RiftEngine/Servo migration behind a hardware compatibility gate; Android System WebView remains the current compatibility renderer until that gate passes.
- Add focused on-device diagnostics and exported test results rather than emulator-heavy CI.

## RiftBuild external-provider boundary

**PROMOTED + FROZEN.** The device-proven build path is external to RiftOS:

1. **DEVICE PROVEN** — external Compile through the project-owned managed compiler registry;
2. **DEVICE PROVEN** — external Preflight;
3. **DEVICE PROVEN** — deterministic external APK Pack;
4. **DEVICE PROVEN** — external APK-v2 Sign using only generic `signing.identity`;
5. **DEVICE PROVEN** — external Verify plus independent RiftOS `RiftApkV2Verifier`;
6. **DEVICE PROVEN** — Android install/launch, Rift++ editor launch, typing, clear, preview, and downstream editor native compile/preflight/pack/sign output;
7. **RETIREMENT LOCKED** — embedded build orchestration, language-specific compile shortcuts, native clang toolchain/prepared-app materialization, APK packing, and APK signing are absent from RiftOS.

The permanent RiftOS side is reusable infrastructure only: `RiftLocalBuildCapability`, `RiftJvmDexService`, `RiftBuildManagedToolchains`, `RiftManagedJvmToolService`, `RiftNativeBufferCompilerService`, `RiftBuildPlatformTools`, `RiftApkV2Verifier`, `RiftBuildInstaller`, and `RiftRappCapabilityBroker`.

Future work may extend this boundary only when device proof demonstrates a missing **generic reusable primitive**. Provider-specific recipes remain outside RiftOS.

### RiftOS-native RAPP lane

**LIVE GENERIC PLATFORM BOUNDARY.** `riftos-app-abi/1` owns normalized app/lifecycle/input events, bounded generic frames, runtime adapters, durable `state.bin`, and permission-gated host effects. `pack-rapp`, `install-rapp`, `launch-rapp`, and `rapp-list` remain platform operations because they package and host RAPP programs, not Android APKs.

`riftpp-generic-v1` is the forward Rift++ adapter; older RPA/RWS adapters remain compatibility lanes. Language semantics stay in adapters/providers rather than the host.

## Semnexis self-hosting bootstrap

Current source is `0.7.0-quickjs-bootstrap`. The installed gate remains `semnexis-bootstrap-self-test/17`, while the source/machine self-hosting frontier has advanced through a real Semnexis-source frontend and semantic-graph slice. Generated ARM32 now parses `fn main() -> i32 { let x = 12 + 3 * (4 + 1); return x; }` into a 12-node Arena AST, lowers it into 22 deterministic semantic facts, and resolves the return `x` NameRef to the local symbol. QuickJS remains only the bootstrap host.

Immediate order:
1. keep installed `/17` claims frozen until the next APK is actually built/installed;
2. expand the Semnexis-written frontend to multiple locals/statements and multiple functions;
3. add function-symbol/call resolution and converge the compact pressure graph onto the canonical Program Graph schema;
4. move graph verification, then baseline type/effect/capability solving, into Semnexis;
5. move Native IR emission/verification into Semnexis and prove bootstrap-vs-self-hosted equivalence;
6. preserve frozen SNIRV0–SNIRV7 compatibility and all ARM32 machine regressions throughout.

## Local Rift MCP expansion

Current source tool family (21 registered tools):

```text
rift_shell_exec
rift_info
rift_stat
rift_hash
rift_list
rift_read_text
rift_write_text
rift_mkdir
rift_remove
rift_move
rift_copy
rift_archive
rift_extract
rift_audit
rift_scan
rift_project_export
rift_workspace_diff
rift_mcp_reconcile
rift_debug
rift_local_agent_batch
rift_workspace_exec
```

Lost-turn recovery is source-implemented through the device-owned `RiftMcpOperationJournal` and read-only `rift_mcp_reconcile`. Journaled calls carry one stable operation identity through ToolHost mutation provenance; terminal identities are not replayed after restart, and response delivery is kept separate from execution completion. After an ambiguous UI freeze/timeout/disconnect, callers should reconcile journal state before issuing another mutation.

The browser compatibility path represents model-facing calls as `[RIFT_CALL]` / `[RIFT_END]` text and returns `[RIFT_RESULT]`; MCP JSON-RPC remains private to trusted transports.

Planned capability families may be added behind explicit local grants:

```text
fs.*
window.*
apps.*
engine.*
kernel.inspect/*
browser.inspect/*
settings.*
logs.*
tests.*
snapshot.*
```

High-impact operations should require explicit developer-mode capability grants. Normal apps and arbitrary webpages must not receive Rift MCP privileges. MCP remains a protocol surface over `RiftToolHost`; it is not the RiftOS kernel API.

## MCP project intelligence

Implemented foundation: compact `RIFT_PROJECT_V2` project descriptor, full workspace reachability through `rift_workspace_exec` (Rift Code Mode), Project Intelligence v2 snapshots, restart-persistent incremental symbol indexing, bounded import/include dependency graph edges, focused project graph and impact views, validation-plan discovery, reference lookup, exact symbol/ranged reads, SHA-256 guarded range/hunk patching, built-in dependency/build/cache ignore rules, dry-run validation, compact changed-file summaries, and copy-on-write rollback for each one-operation `rift_workspace_exec` mutation. Public multi-op/batch execution is currently fail-fast disabled because it can hang the agent/runtime; coordinated changes must use explicit sequential calls.

Project Intelligence v2 deliberately stays behind the existing `project` Code Mode operation. `kind=graph`, `kind=impact` and `kind=validation` reuse the already-published `kind`/`query` operation fields, so no parallel agent tool catalog or second task controller is required.

Next improvements should stay lightweight:

- richer language-aware semantic resolution for aliases, generated sources and package/module namespaces without turning RiftOS into a heavyweight language-server host;
- use project impact output to select the smallest relevant local test/check set before external Android/native builds;
- add deliberate source-control-oriented review surfaces only if they have a real caller/UI and tests, rather than reviving an unreachable hidden session journal.

The project should never be injected wholesale into ChatGPT. RiftOS exposes full project reachability through the local executor; only bounded search/read results needed for reasoning cross the existing ChatGPT Web transport.

## Native RiftCLI engineering program

The former 14-patch Experimental RiftCLI lifecycle program was retired on 2026-09-19. Its Kotlin lifecycle/swarm/IR/research/parity/verification implementation is no longer the active roadmap.

Reusable RiftOS evidence foundations remain live and independent:

1. **Diff Engine V2 — retained.** Workspace Records owns bounded deterministic multi-hunk evidence.
2. **File identity intelligence — retained.** Rename/copy/rewrite correlation remains Workspace evidence.
3. **Patch sessions and provenance — retained.** Explicit writer provenance remains shared by MCP/Shell/Files/Dev Lab/Git.
4. **Patch Manifest/tamper evidence — retained.** Candidate/tree/change-set hashing and record-chain primitives remain reusable.
5. **Project Intelligence V2 impact mapping — retained.** Current source graph/impact/validation evidence remains the canonical live project-intelligence owner.

RiftCLI is now rebuilt as a native C++ subsystem with this promotion sequence:

- **N0 Native bootstrap — proven.** C++ core + thin Kotlin JNI host, ARM64 primary + ARM32 compatibility, explicit process-local enable, exact native source snapshot, final APK native packaging, real `armeabi-v7a` device execution, fail-closed unsupported command handling, and force-stop/restart reset were proven on RiftOS run #250 (`6f7a6129…`).
- **N1 Driver Protocol — proven.** Bounded external-driver request-id/session/task/project/evidence/action contract. Builder run #255 / source `121edf6b…` proved full bounded RiftOS authority, replay protection, shell/ToolHost jobs, truthful cancellation, provenance, one global outstanding authority slot and bounded external continuation on the ARM32 target.
- **N1.5 Persistent push/events — LIVE-PROVEN on installed Android source `0814fb8cf8ca31186d6e639fc7ad3b965d822897` (2026-09-21).** The persistent device WSS now carries sequenced RiftCLI lifecycle events to the Cloudflare Durable Object, with bounded device-owned replay, per-subscriber WebSocket/SSE cursors, ACKs, reconnect recovery and inline terminal results. Live proof used an external Chrome SSE subscriber with `sseClients: 1`; a read-only `version` job produced `job.submitted` / `job.started` / `job.completed` sequences that DebugHub independently recorded as `event.created -> cli.event.send queued -> cli.ack received`, and the subscriber received the same sequences without polling. Replay proof then forced `sseClients: 0`, created events while disconnected, and reconnected with the prior cursor; only the missed sequences replayed, in order, with no duplicate/older event. Poll/list/cancel remain recovery/debug fallback controls. No Durable Object event-payload writes.
- **N1.6 RiftCLI Batch V2 — historical proof only; retired in the current rollback branch.** Builder #259 / source `eaa2a390...` remains historical evidence that the former CLI-owned batch design worked, but current RiftCLI reports `batchV2=false`, `batchV2MaxSteps=0`, `batchOwner=riftos-local-agent`, and the `rift_cli_batch` dispatcher fails closed. **Current batch owner: RiftOS Local Agent — source-complete, new build/install proof pending.** `rift_local_agent_batch` prevalidates at most 16 fixed-scope steps, binds request IDs to exact normalized plans, owns one process-local Local Agent execution lease, persists bounded results/status atomically, supports cancellation/result paging, never replays unfinished jobs after restart, and does not resurrect RiftShell `batch` or multi-op `rift_workspace_exec`.
- **N1.7 abuse/restart/backpressure/batch stress — next promotion gate; zero-poll steady-state lock implemented in source.** Basic SSE reconnect/replay, ACK correlation and duplicate-free cursor recovery are already proven under N1.5. Current source now hard-locks `driverObservationMode=persistent-push-steady-state`, `automaticPolling=false` and `pollFallbackOnly=true`; regression coverage also requires that the only poll call sites remain the explicit `rift_cli_job_poll` fallback path rather than an internal polling loop. This hardening still requires Builder/install proof before it is called installed. Remaining N1.7 stress work covers forced relay/device restarts, repeated reconnect gaps, slow-subscriber/backpressure handling, subscriber caps, large-result fallback, cancellation between batch steps, global no-interleave behavior, concurrent-driver pressure, both failure policies, bounds and cleanup on the installed build.
- **N2 Federated Rift Memory Kernel — HARD PRE-N3 PROGRAM.** Build one canonical evidence/event/transaction/reconciliation authority with multiple rebuildable specialist cognitive engines rather than one monolithic algorithm or competing truths. N2 starts with a replaceable SQLite reference `MemoryStore`, then develops RiftStore as an experimental backend that may replace SQLite responsibilities only when identical benchmarks prove a real correctness/resource/performance win. N2.0-N2.12 cover the canonical JSON model, immutable/content-addressed evidence, bi-temporal history, trust/reconciliation, temporal graph, episodic/consolidation/semantic/belief/skill/failure/causal/commitment/predictive lanes, router/fusion/arbitration/Context Compiler, Observer+Validator closed loop, poisoning/fsck/crash/rebuild/scale hardening, public/private benchmarks, incremental hybrids and ablations. Full specification: `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`.
- **N3 Architecture/impact engine — BLOCKED until N2.12 promotion.** N3 may consume Project Intelligence, owner contracts and the promoted Rift Memory Kernel only after N2 proves canonical integrity, project isolation, provenance, reconciliation, crash/restart safety, poisoning resistance, projection rebuild, procedural learning and benchmark/resource targets on the real Android target. Architecture or happy-path demos do not waive this barrier.
- **N4 Planner.** Professional dependency-aware planning with explicit preconditions/postconditions and recovery. Plan globally; mutate incrementally. No opaque multi-operation batch editing.
- **N5 Research/evidence.** Native bounded source/claim/assumption/freshness ledger with authoritative-source and independent-verification requirements.
- **N6 Verification.** Candidate-bound compile/test/security/dependency/docs/build/APK/device/performance evidence as applicable.
- **N7 Adversarial engineering loop.** Corrupt/stale memory, renamed files, dependency cycles, failed/interrupted builds, misleading tests, dirty repos, malformed driver input, restart/resume and huge-project stress. Every discovered defect becomes a regression test.

No later gate is promoted merely because a happy-path demo passes. Documentation and regression tests are part of each gate.

## RiftEngine

Move RiftBrowser toward a lightweight Rust-native engine based on Servo:

1. define a renderer-neutral `RiftBrowserEngine` boundary;
2. integrate RiftEngine/Servo on supported Android versions;
3. validate ChatGPT login/cookies/streaming and long-chat memory behavior;
4. port the exact-origin MCP compatibility adapter;
5. verify file chooser/download/navigation/window resizing;
6. keep WebView only as a compatibility backend until RiftEngine is proven.

See `docs/RIFTBROWSER_ENGINE_MIGRATION.md`.

## Next: RiftScript Studio

Build a privileged RiftOS developer application inspired by Blender's scripting workspace, but scoped to the RiftOS engine.

Planned surfaces:

- live JavaScript/module editing and hot reload,
- live CSS/DOM editing,
- RiftDesktop/window inspector,
- RiftKernel/service inspector,
- RiftFS/RiftWorkspace explorer and editor,
- RiftBrowser state/console inspection,
- structured logs and event tracing,
- reusable `.rift.js` developer scripts,
- in-device test runner,
- developer overlay filesystem with revert/commit,
- Development Snapshot export containing source state, diffs, diagnostics, tests and logs.

Native Kotlin/manifest/Gradle source may be edited/staged/exported in RiftScript, but compiled Android code still requires a new APK build before it can become active.

## Build handoff

A future Development Snapshot should be directly patchable outside the phone:

```text
RiftScript Studio
   -> export snapshot/patch
   -> GitHub / external patching
   -> Android APK build
   -> signed artifact
```

The Android SDK/Gradle toolchain should remain outside the installed phone app to avoid recreating large on-device build/runtime bloat.

## Longer term

- stable RiftRT ABI and richer surface/input primitives,
- app package signing/trust metadata,
- crash/session diagnostics export,
- controlled native plugin packaging compiled into RiftOS,
- more complete tablet/desktop multi-window ergonomics,
- richer local project intelligence while preserving the ChatGPT-Web-only model path.

## Non-goals

- bundling a full Android SDK/emulator into RiftOS,
- arbitrary downloaded native ELF execution,
- giving normal webpages unrestricted Android or RiftFS access,
- restoring the removed DOM Agent V1/V2/V3 protocol,
- adding an undeclared direct model API/key path or hidden AI task controller,
- making MCP the internal RiftOS capability API,
- reintroducing the removed local LLM runtime on low-memory Android devices.

## RiftDebugHub integration

Foundation complete in current source:

- process-owned passive hub;
- bounded spans/events and secret-key redaction;
- reusable `RiftDebugAdapter` plug;
- MCP Server -> Tool Host trace propagation;
- one read-only `rift_debug` query surface;
- focused source/wiring regression and exact Gradle source declaration.

Next integration work is intentionally subsystem-by-subsystem: RiftCLI, Local Agent validation, RiftShell, RiftFS, Git, Builder, Binder bridges and Accessibility emit through adapters without routing execution through the debugger.
