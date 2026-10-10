# RiftOS Bootstrap Host Migration — source checkpoint (2026-10-09)

## Goal
Keep a small, stable Android APK host that loads Core, Shell, system services, UI and runtimes as versioned, independently replaceable components. Kotlin/Java/C++ remain supported; Rift++ is optional. This is **not** RiftOS-Dev.rapp and does not use any retired RiftCLI / legacy compiler or APK packaging paths.

## What actually exists in this checkpoint
- `RiftCoreApplication` now enters `RiftBootstrapHost` for main Core process startup and for the `:riftShell` WebView preparation hook.
- With no external component activation, the host calls the **same embedded production** Core rollback recovery, Core registry recovery, Core initialization, shell recovery, relay start, and Shell WebView preparation in the same order as before.
- The host provides `RiftBootstrapEntry.start(Application)` and an **optional process-start-only** DEX component selector for `core` or `shell`. It validates a small activation record, fixed app-private DEX path, size, immutable-file requirement, SHA-256 digest, interface implementation and a synced interrupted-boot marker. An invalid record fails back to embedded. If the selected entrypoint itself throws, it is **not** re-entered in the same process; a subsequent process launch sees the marker and uses embedded.
- This is **not live hot swap**. Nothing has been extracted from the APK in this checkpoint. `RiftShellActivity`, `RiftCoreSurfaceIpcProvider`, their Android manifest declarations, RAPP execution, native code, and built-in UI still ship in the APK. No external component is installed, activated, built or device-proven. Failures after a successful `start()` return are not yet rollback-detected.

## Experimental component layout (no installed component yet)
Under Android app-private `filesDir/bootstrap-components/`:
- `core.dex` or `shell.dex`: a read-only separately compiled DEX implementing the host-shared `com.riftos.app.RiftBootstrapEntry` interface.
- `core.json` / `shell.json`: the matching activation record.
- `core.booting` / `shell.booting`: fail-safe interrupted startup marker, never silently ignored.

Activation contract example for a future separately built **core** module:

```json
{
  "schema": "riftos.bootstrap-module/1",
  "api": 1,
  "component": "core",
  "entrypoint": "com.example.riftos.modules.CoreEntry",
  "sha256": "<64-lowercase-hex-of-core.dex>"
}
```

There is **no active module installer/promoter** yet; do not manually write these files into production or claim that this is production-ready. The host currently uses a built-in compatibility Core, not an external one. Compiling an arbitrary Kotlin JAR without Android-compatible DEX conversion will not satisfy the contract. The parent classloader must retain the tiny shared ABI only, so moving actual Core classes outside the APK requires removing their duplicate definitions from the parent.

## Required completion gates within the coordinated architecture migration
1. **Process/bootstrap ABI proof**: compile host, verify unchanged embedded boot, remote shell restart, RAPP persistence, one-use C1.4 approvals and C2-A registry rollback on real Android. User manually operates the existing signed Builder; never dispatch Builder or install an APK automatically. C2-A still requires explicit device PASS.
2. **Actual install/activate/rollback**: implement one owner for import/staging/verification, journaled revision pointers and crash-loop detection, one-use user intent, versioned Core-to-host IPC; prove a replaceable trivial service module on the physical device. No implicit RAPP admin grant.
3. **External Core**: split portable policy/runtime state out of APK-only Android adapters, move execution implementation into one external component, keep Core process owner and working C:/D:, install/uninstall, RAPP generations and recovery snapshots. Test Core process restart and rollback.
4. **External graphical Shell**: keep manifest Activity as host-owned Android adapter, extract desktop/window manager and shell implementation, prove `:riftShell` PID replacement without touching running Core/RAPP execution. Do not confuse classloader swaps with live swapping.
5. **Native C++ and additional services**: separate ABI-versioned `.so` where appropriate; on Android do not attempt unsafe `dlclose` replacement of live code. Use new process/restart boundaries. Retire embedded compatibility copies and update Builder source lists only after actual external replacements are verified.
6. **Final milestone**: full signed Builder, physical device regression (boot, Core/Shell independent restarts, RAPP lifecycle, retained apps/data, filesystems, consent rollback, discovery/registry, native UI, all fallback cases), then promote. One coordinated branch does **not** mean proof can be skipped.

## Non-negotiable caveats
- Android still owns Activity, service, provider registration and app sandbox/permissions. A generic module cannot add a new Android manifest component or break app-private filesystem isolation without a host bridge.
- External dynamically loaded DEX in the host process shares the host's privileges and crash fate. Isolate critical services in remote Android processes when safe, rather than assuming DEX classloader isolation.
- This host does not grant root/full CPU/RAM access, does not replace Android, and does not magically turn Kotlin/Java/C++ into Rift++ VM bytecode.
- Existing C1.4-C2-A signed #679 O_EXCL rollback path and C2-B2 HOLD remain separate. Do not lower its proof bar or overwrite provider registry, and do not touch the pre-existing manually dispatched Builder workflow.
- Keep the current embedded fallback and pre-migration backup until the user has signed-device evidence for external Core/Shell.
