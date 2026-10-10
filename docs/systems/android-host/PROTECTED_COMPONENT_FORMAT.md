# Independent protected Core / graphical Shell artifacts — format and trust contract

**Checkpoint:** 2026-10-10 SOURCE implementation in progress. The following is a format and host staging contract, not a claim that a real external RiftOS Core or feature-complete graphical Shell artifact has been compiled, installed, or accepted.

## Exact input directory and independent build ownership

The native trusted **Admin Approvals** UI reads these independent build outputs from the RiftOS D: volume:

- `/D:/Builds/Components/core/component.json` plus `core.dex`
- `/D:/Builds/Components/shell/component.json` plus `shell.dex`

The Android APK does **not** compile these modules at installation time. Core/Shell must be compiled separately by the supported registered compiler pipeline (ultimately the native Rift++ component toolchain). The user manually triggers any full signed RiftOS Builder APK job when the **host** itself changes. Ordinary compatible external component revisions should eventually be installed through this protected component path **without** a RiftOS APK rebuild.

A `component.json` is strict UTF-8 JSON with **exactly** these fields: `schema` (literal `riftos.protected-component/1`), `component` (`core` or `shell`), `version` (version identifier), `entrypoint` (fully qualified Java-compatible class, exactly `com.riftos.external.core.…` or `com.riftos.external.shell.…`), `sha256` (64 lowercase hex characters for the DEX), `buildSha256` (64 lowercase hex content/build provenance), `payload` (exact `core.dex` or `shell.dex`), `abi` (currently 1), `minSdk`, `maxSdk` (integers bounding the physical device API). No arbitrary file paths, requested privileges, package install hooks, dependency injection or unfixed class namespaces are accepted.

`RiftProtectedComponentManifest.identityDigest()` binds all manifest fields, while the staged DEX is independently rehashed. The verifier examines Android DEX type IDs and defined classes to reject unexpected packages, embedded `com.riftos.app.RiftCoreRuntime` / execution-class references, cross-component classes, wrong ABI and mismatched entrypoint. It links the candidate class through an isolated `DexClassLoader` **without invoking its constructor or app operations**, and checks the interface(s). This binary check is **not** a guarantee against malicious dynamic reflection or proof of correct RAPP behavior; the candidate is trusted same-UID code and **real device tests remain mandatory**.

## Native admin staging and one-use approvals

The user must use **trusted graphical Admin Approvals**. The host authenticates the installed app signer, actual Binder PID, `:riftShell` Android process identity, foreground lease, exact target scope, 45-second validity and one-use consumption. A generic RAPP, terminal command or ordinary external DEX cannot request the protected release operation.

1. **Choose** an exact `component.json` and fixed DEX source. The UI selects `component://stage/{manifestIdentityDigest}` and requires a fresh approval.
2. **Stage** via read-only Android file descriptor; Core revalidates the signed scope and exact manifest, seals content-addressed bytes, rejects APK-owned Core/Shell execution references and persists a SHA-specific structural qualification/manifest receipt. This stage **executes no external component code**.
3. **Select activation** `component://activate/{component}/{sha256}`, ask for **separate** one-use approval, and have the protected Core owner create pending release journal before publishing exact private `core.json` or `shell.json` pointer. **No live in-process hot swap occurs.** The selected component changes only on a controlled Android process restart.
4. On an external candidate's first start, the host verifies receipt, ABI, sealed SHA and entrypoint before executing its constructor. A durable `core.booting` or `shell.booting` marker remains until later explicit device acceptance; an interrupted startup selects embedded for that boot and prepares previous verified external N-1 for the next eligible boot.
5. **Only after actual physical-device proof**, choose exact `component://accept/{component}/{sha256}`, request a **third** one-use approval and accept. For Core the host checks the exact selected SHA in the live Core process. For Shell the host checks the exact external graphical revision and OS-attested active Shell PID. Only then does the durable journal mark this revision `lastKnownGood` and clear its unaccepted startup marker. Merely staging/activating or passing the binary closure check cannot automatically promote a revision.

## Recovery and crash evidence

The host ledger keeps a `pending` revision, previously `lastKnownGood`, and an older `previousKnownGood` plus rejected SHA/reason. **Pending failure** rolls back to the last accepted external revision; an **already accepted** revision's fatal failure can demote to the previous accepted external revision. Protected recovery revalidates both sealed DEX hash and exact SHA-specific qualification, restores the pointer atomically, and clears the stale boot marker to avoid a retry loop. If the N-1 artifact/receipt is missing or tampered with, the host falls back to embedded **while embedded code remains in the migration APK**; final embedded removal is forbidden until external N-1 and recovery are fully device-proven.

An uncaught Core Java exception delegates to Android's original fatal handler after capturing available evidence. The main-process bootstrap can match Android 11+ historic crash/native-crash/ANR exit reasons to its exact recorded external Core SHA/PID and attempt recovery on its next permitted startup, without treating ordinary user exits/force-stop as a crashed component. `RiftCoreShellRecovery` detects an absent real `:riftShell` process (not just a late heartbeat) and can restore prior external Shell while retaining Core/RAPP state. The separate `:riftCoreSupervisor` is **best effort** and Android can terminate it; there is no magical always-on watchdog or universal access to full native tombstones.

## Required blockers before completion

**Not delivered by the format/host source itself:** actual independently compiled *complete* external Core RAPP lifecycle/runtime/FS state graph with zero embedded execution, actual full external desktop/window manager/files/browser/IME/graphical app handling, a reproducible standalone compiler artifact route with closure/ABI dependencies, and physical tests of crash loops, process death, accepted N-1 fallback, live RAPP survival, 32-bit/64-bit native support if applicable. Existing APK-owned Core and Shell implementation classes must remain until these are proven. The requirement “ordinary Core/Shell updates never rebuild RiftOS” becomes valid only **after** host and required ABIs are signed/device-accepted.

Full gate sequence and evidence: [EXTERNAL_CORE_SHELL_ROADMAP.md](EXTERNAL_CORE_SHELL_ROADMAP.md).
