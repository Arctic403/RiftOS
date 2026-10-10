# RiftOS Bootstrap Host Migration — 2026-10-09

## NEW checkpoint — Core-authorized native proof import + manual separate DEX build (SOURCE ONLY)

With manually signed RiftOS #682 live, Core PID 4256, Shell PID 4312, 2 retained RAPPs, no active module and no journal, the user authorized building the first trusted probe-import path. Source now adds **only** fixed `ProbeV1` workflow; no generic runtime/software provider registration or external Core/Shell replacement is permitted.

The existing native Admin Approvals window exposes an Android SAF document picker and **two different** 45-second, signer- and PID-bound one-use approvals: `bootstrap.probe.stage` targeting exact `bootstrap://probe/ProbeV1` and `bootstrap.probe.activate` targeting `bootstrap://probe/<64-character SHA-256>`. File-picker selection carries a read-only `ParcelFileDescriptor` through the already protected Core ContentProvider; Core reads/verifies/immutable-stages the DEX from the descriptor rather than accepting a shell-selected pathname. A second explicit approval activates that SHA and starts only the previously nonexported `:riftBootstrapProbe` Android Service. Core/Shell/RAPPs remain embedded; the service is separate-process, same Android UID. Stage success does not execute the payload. Both Core actions require the existing OS-attested exact Shell PID, installed APK signer, foreground Shell recovery lease, ticket TTL/one-use matching, and durable proof audit; new proof operation names **do not** expand the `RESTRICTED` privileged-operation allowlist.

`bootstrap/probe-proof/build-probe.sh` compiles the Java proof externally using JDK javac, a compile-only RiftBootstrapEntry ABI stub and Android D8, producing a raw `probe-v1.dex` plus SHA-256 sidecar with NO host class bundled. `.github/workflows/build-bootstrap-probe.yml` is **workflow_dispatch only**, a separate manual artifact job which does not invoke/modify the existing user-manual RiftOS APK Builder. The user will download the artifact and choose `probe-v1.dex` via Android picker. No DEX has been compiled, installed or run through this new path yet. Core `status.bootstrapHost.probeProof` reads a bounded fixture receipt with revision/PID/process; the prior marker is deleted at activation, but the final result still needs real Core audit/process/PID verification on-device.

**Device acceptance sequence after a future green signed user-manual APK:** (1) confirm Core and Shell PIDs, 2 retained RAPPs, zero general grants/registry, no activated external Core/Shell; (2) user manually triggers the independent DEX artifact workflow and obtains raw DEX; (3) native Admin Approvals → choose DEX → request `stage` scope → Allow Once → Stage; (4) select SHA-bound activation scope → request NEW Allow Once → activate/start separate probe; (5) Core audit must show stage and activation one-use, `core status.bootstrapHost.probeProof` revision `probe-v1` and `:riftBootstrapProbe` PID distinct from Core and Shell, no leftover boot marker; (6) negative proof of denied/expired/replayed approval and invalid/mismatched DEX, then crash rollback; no Core/RAPP/Shell interruption. **No claims of passed live DEX proof until all evidence exists.**

## Latest — content-addressed staged components, **source-only, not device proven**

User-signed #680 source `494be44e6348` is green/installed. Live MCP confirmed Core PID 22786 and Shell PID 22747, two retained RAPPs and default embedded bootstrap flags; the user's native Admin Approvals screenshot plus independent Core audit proved C2-A `requested → approved → consumed → rolled-back` with `registryRestored`, zero journal, registry, providers or grants. **C2-A positive DEVICE PASS**; denial/replay/expiry negative test matrix remains pending. This is distinct from actual C2-B2 production third-party runtime registration (still held for its own design/proof).

The next source checkpoint adds `RiftBootstrapComponentStore` under Android app-private `bootstrap-components`. `stage` accepts bounded raw Android DEX bytes through an internal `InputStream`, validates the DEX prefix, computes SHA-256, seals the file read-only and preserves it under `<component>-<sha256>.dex` without changing any active revision. `activateProbe` verifies staged content and writes a versioned `probe.json` through Android `AtomicFile`, with an independently journalled `probe.previous.json` fallback; the next Core process boot may load the probe through the host-owned `RiftBootstrapEntry` ABI. Boot marker `probe.booting` detects an interrupted probe entrypoint, and `recoverProbe` restores the previous activation or embedded no-op proof. `resetProbe` clears activation after a safe non-interrupted state. Loader checks the complete hash, read-only DEX, schema/API and class ABI, and rejects a class name already loaded from the APK parent classloader.

**Critical safety gate:** external activation is currently **disabled for Core and Shell**, even if `core.json` or `shell.json` exists. The original Core/C1.4 recovery, Core RAPP executor, graphical Shell and Android-bound services continue as embedded APK implementations. Only the noncritical `probe` slot can run experimental external code, in a separate non-exported `:riftBootstrapProbe` Android Service process. The service is inert until explicitly started by a future authorized native proof flow; Core does not automatically start it. The service shares the APK UID/files but not Core's process crash fate. No native UI, Core-authorized installer IPC, approval ticket, real external DEX artifact, process restart test or proof of a loaded probe has been delivered. Internal staging API is not reachable from untrusted RAPPs or the terminal. No in-process code replacement is supported.

**Next proof:** implement trusted native import/consent on the Core side (no general RAPP admin), prepare an external DEX probe via the canonical external toolchain, independently verify package/stage/activate/boot/recover on the physical device; THEN plan extraction of Core/Shell. Android `AtomicFile` safety and on-device DEX loading are SOURCE hypotheses until installed proof. Do not mark a green APK or source scan as an external module/device pass. Current Builder remains user-dispatched only, and old compiler paths remain prohibited.

## Goal
Keep a small, stable Android APK host that loads Core, Shell, system services, UI and runtimes as versioned, independently replaceable components. Kotlin/Java/C++ remain supported; Rift++ is optional. This is **not** RiftOS-Dev.rapp and does not use any retired RiftCLI / legacy compiler or APK packaging paths.

## What actually exists in this checkpoint
- `RiftCoreApplication` now enters `RiftBootstrapHost` for main Core process startup and for the `:riftShell` WebView preparation hook.
- With no external component activation, the host calls the **same embedded production** Core rollback recovery, Core registry recovery, Core initialization, shell recovery, relay start, and Shell WebView preparation in the same order as before.
- The host provides `RiftBootstrapEntry.start(Application)` and an **optional process-start-only** DEX component selector for the separate `probe` service. Critical external `core` and `shell` are deliberately disabled. It validates a small activation record, fixed app-private DEX path, size, immutable-file requirement, SHA-256 digest, interface implementation and a synced interrupted-boot marker. An invalid record fails back to embedded. If the selected entrypoint itself throws, it is **not** re-entered in the same process; a subsequent process launch sees the marker and uses embedded.
- This is **not live hot swap**. Nothing has been extracted from the APK in this checkpoint. `RiftShellActivity`, `RiftCoreSurfaceIpcProvider`, their Android manifest declarations, RAPP execution, native code, and built-in UI still ship in the APK. No external component is installed, activated, built or device-proven. Failures after a successful `start()` return are not yet rollback-detected.

## Experimental component layout (no installed component yet)
Under Android app-private `filesDir/bootstrap-components/`:
- `<component>-<sha256>.dex`: immutable, content-addressed separately compiled DEX implementing host-shared `com.riftos.app.RiftBootstrapEntry` (currently only `probe` is loadable).
- `probe.json`: Android AtomicFile activation pointer and `probe.previous.json`: previous/embedded rollback record. External `core.json` and `shell.json` are not activated.
- `probe.booting`: fail-safe interrupted proof startup marker, never silently ignored.

Activation contract example for a separately built **probe** module (no installer UI exists yet):

```json
{
  "schema": "riftos.bootstrap-module/1",
  "api": 1,
  "component": "probe",
  "entrypoint": "com.example.riftos.modules.ProbeEntry",
  "sha256": "<64-lowercase-hex-of-probe-revision.dex>"
}
```

There is **no trusted user-facing module importer/consent/IPC** yet; only the internal stage/activateProbe code exists. Do not manually write activation files into production or claim this is ready for arbitrary program installation. The host currently uses a built-in compatibility Core, not an external one. Compiling an arbitrary Kotlin JAR without Android-compatible DEX conversion will not satisfy the contract. The parent classloader must retain the tiny shared ABI only, so moving actual Core classes outside the APK requires removing their duplicate definitions from the parent.

## Required completion gates within the coordinated architecture migration
1. **Process/bootstrap ABI proof**: compile host, verify unchanged embedded boot, remote shell restart, RAPP persistence, one-use C1.4 approvals and C2-A registry rollback on real Android. User manually operates the existing signed Builder; never dispatch Builder or install an APK automatically. C2-A positive execution has signed-device PASS on #680; negative consent/replay/expiry and other process/recovery cases remain pending.
2. **Actual install/activate/rollback**: implement one owner for import/staging/verification, journaled revision pointers and crash-loop detection, one-use user intent, versioned Core-to-host IPC; prove a replaceable trivial service module on the physical device. No implicit RAPP admin grant.
3. **External Core**: split portable policy/runtime state out of APK-only Android adapters, move execution implementation into one external component, keep Core process owner and working C:/D:, install/uninstall, RAPP generations and recovery snapshots. Test Core process restart and rollback.
4. **External graphical Shell**: keep manifest Activity as host-owned Android adapter, extract desktop/window manager and shell implementation, prove `:riftShell` PID replacement without touching running Core/RAPP execution. Do not confuse classloader swaps with live swapping.
5. **Native C++ and additional services**: separate ABI-versioned `.so` where appropriate; on Android do not attempt unsafe `dlclose` replacement of live code. Use new process/restart boundaries. Retire embedded compatibility copies and update Builder source lists only after actual external replacements are verified.
6. **Final milestone**: full signed Builder, physical device regression (boot, Core/Shell independent restarts, RAPP lifecycle, retained apps/data, filesystems, consent rollback, discovery/registry, native UI, all fallback cases), then promote. One coordinated branch does **not** mean proof can be skipped.

## Non-negotiable caveats
- Android still owns Activity, service, provider registration and app sandbox/permissions. A generic module cannot add a new Android manifest component or break app-private filesystem isolation without a host bridge.
- External dynamically loaded DEX in the host process shares the host's privileges and crash fate. Isolate critical services in remote Android processes when safe, rather than assuming DEX classloader isolation.
- This host does not grant root/full CPU/RAM access, does not replace Android, and does not magically turn Kotlin/Java/C++ into Rift++ VM bytecode.
- Existing C1.4-C2-A #680 positive O_EXCL rollback DEVICE PASS and C2-B2 HOLD remain separate. C2-A negative gates remain pending. Do not lower its proof bar or overwrite provider registry, and do not touch the pre-existing manually dispatched Builder workflow.
- Keep the current embedded fallback and pre-migration backup until the user has signed-device evidence for external Core/Shell.
