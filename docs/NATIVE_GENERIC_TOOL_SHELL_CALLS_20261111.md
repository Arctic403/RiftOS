# Native RiftShell Generic Tool CLI (source candidate, 2026-11-11)

## 2026-11-11 follow-up: explicit temporary trust

The original `tool list` and `tool call` native commands remain intact.
A new **unbuilt APK Host source revision** adds:

```text
tool trust start riftbench-bridge status,submit,result 10
tool trust status riftbench-bridge
tool trust stop riftbench-bridge
```

`tool trust start` sends `tool-session-start` with the named operations
and 1..15-minute TTL to the selected external Core. **Core v0.6.22
is required**; the currently installed Core v0.6.21 intentionally does
not support the new verbs. The Core requires explicit consent once,
checks saved operations + signer identity, and retains trust only in
memory. This is not a permanent always-allow policy; RAPP calls remain
per-call consent and listed operations may still modify data (especially
RiftBench `submit`). Stops and expiry revoke the session. The host
contains no local trust cache or direct Binder path.

This follow-up requires **two manual user builds**: GitHub RiftOS
Host APK (the Riftos-builder workflow), and independent Core v0.6.22
from `/D:/Workspace/RiftComponents/compile-core-v0622.json`.
The external graphical Shell and RiftBench Android companion remain
unchanged. Neither build has been triggered by the assistant.

## User authorization and scope
User requested **Core or Shell upgrade** so tool calls to approved Generic Tools can be issued directly through RiftShell/MCP rather than manually through the Generic Tool Connections form. This is an explicitly authorized **host-owned native RiftShell** change, not an external selected Core or graphical Shell upgrade.

**Changed code:** `/D:/Workspace/RiftOS-main/android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` only.
**Backup:** `/D:/Workspace/RiftBenchBridge/backups/RiftNativeShell_before_native_tool_calls_20261111.kt`.
**Test harness:** `/D:/Workspace/RiftOS-main/docs/docs/tests/native-shell-generic-tool-source-test.js`.
**Selected device:** External Core v0.6.21 and existing external graphical Shell unchanged; no host APK build/installation/staging was attempted.

## Why this host change (not independent Core v0.6.22)
- Selected independent external Core v0.6.21 already exposes `RiftCoreExternalAppConnectionsV1.appConnectionRequest` and `IndependentCoreGenericToolsV2` with Core-owned saved registry, signer pinning, operation allowlist and **per-call UI consent**.
- Graphical independent Shell v14 and later already provide file-backed Generic Tool calls in their *own graphical Terminal*, with Android device proof from Oct 22. Do **not** duplicate that into another external Core release.
- The **host-owned native RiftShell** (`RiftNativeShell.kt`) is the separate parser used by RiftOS MCP `rift_shell_exec`. Its prior `help` and `executeNative` had no `tool` dispatch, so MCP calls returned unsupported. Only a host-owned parser update can expose the command to this MCP route.
- The native shell executes inside the selected Core host process and routes `tool list/tool call` into the already selected independent Core via `RiftHostCoreComponents.externalAppConnections().appConnectionRequest(appContext, request)`. It does not call Binder providers directly, bypass the signer checks, create approvals, or add generic tool operations.

## New native terminal commands (after a new host APK is installed)

```text
tool list
tool call riftbench-bridge /D:/Workspace/RiftBenchBridge/commands/status.json
tool call riftbench-bridge /D:/Workspace/RiftBenchBridge/commands/project-summary.json
tool call riftbench-bridge /D:/Workspace/RiftBenchBridge/commands/list-cubes.json
tool call riftbench-bridge {"schema":"rift.riftbench-tool/1","operation":"result","requestId":"rb1"}
```

Use the actual requestId from the `project-summary` submission instead of a guessed ID. The provider's `submit` is asynchronous; follow with `result` and repeat only while status remains `pending`.

Reusable provider requests are at `/D:/Workspace/RiftBenchBridge/commands/`: `status.json`, `project-summary.json`, `list-cubes.json`, `export-info.json`, `selftest.json`. These files contain **no pairing key** or secrets.

Commands are **provider neutral**. Unlike the separate UI form, native Shell requires a complete provider request JSON with `schema` and exactly one `operation` or `op`. It does not infer schema or approved operations from a provider name.

## Security and confinement
- Core alone checks saved tool approval, pinning of installed APK signature, approved verb scope and live user consent for every `tool-call`.
- The command does not accept `tool attach`, `tool update` or `tool detach`. Saved registry management stays in the UI with Core consent.
- IDs must match `^[a-z][a-z0-9-]{1,39}$`.
- Request files must be absolute `.json` under /D:/Workspace, /D:/Documents or /D:/Projects; reject traversal, `//`, symlinks and canonical escape, nonfiles and files >16,384 bytes. Complete inline request JSON may be passed with 16,384-byte maximum.
- Native request to Core <=24,576 bytes, response <=256 KiB; Core further bounds provider Binder message and response.
- No arbitrary Linux shell, file system permission expansion, token handling, automation or persistent grant is introduced.
- Headless MCP usage might leave the existing consent UI unavailable; in that case Core denies/times out. Do not try to bypass approval to make automation work. Where authorization is required, ensure RiftOS's consent surface is visible.

## Evidence
- Pure JS structural + command grammar test harness: **35/35 passed** in independent V8 execution against patched `RiftNativeShell.kt`, Core `IndependentCoreGenericToolsV2.kt`, and five pre-authored provider commands.
- RiftOS host source health scans: 123 files checked. One existing static scanner finding for the filename `RiftSecretStore.kt` (“sensitive-looking filename”), unrelated to this patch; NO specific finding against the native shell change.
- Actual Android/Kotlin compilation, Gradle tests, signed host APK, install/update, IPC consent path, and live RiftBench calls **not yet run**. No claims of device qualification.
- The source is an *in-place local workspace modification*. The installed host remains unchanged until the user's own builder workflow packages this source and the user verifies/signs/installs it. If the host builder uses a remote Git checkout, the user must first arrange for the exact reviewed source to be available there; **no GitHub push/commit has been performed**.

## Manual release/rollback
1. Review changed source against backup; run `docs/tests/native-shell-generic-tool-source-test.js` and full Android Gradle tests in the normal host builder.
2. Use **Riftos-builder-main** (host Android APK), **not** the External Core & Shell Builder RAPP or RiftBuild Hosted generic companion source build. Ensure the builder is using the reviewed source, run compile/preflight/pack/sign/verify, and inspect matching source SHA and signing identity. Build both ARM ABI variants as usual.
3. Manually install signed host update after prior host/Recovery backup. Do not change external Core/Shell selected DEX.
4. With RiftOS UI consent available and RiftBench connected, call `tool list`, then `tool call riftbench-bridge /D:/Workspace/RiftBenchBridge/commands/status.json` via native shell. Expect Core `tool-call` with `remote.result.running` and `remote.result.browserConnected`. Test positive read-only `project_summary` and asynchronous `result`.
5. Negative tests: unknown ID, operation not in saved approved scopes, signer substitution, denied consent, malformed JSON, path traversal, oversized file, detached provider. Confirm fails closed.
6. Regression: graphical independent Shell terminal, RiftFS read/write, Core status, Build/Recovery, MCP other commands.
7. On failure, use manual Recovery/previous signed host; restore the pre-patch source backup if reverting the repository. Never auto-install/promo.

## Important compatibility note
The host Core Shell IPC `METHOD_SHELL_EXECUTE` has a 5-second outer wait, while tool consent may take up to 80 seconds. This new CLI route is primarily for native MCP `rift_shell_exec` (or persistent RiftShell job submission), not a replacement for the existing graphical independent Shell Terminal's dedicated `externalAppRequest` route. For consent dialogs use the established graphical Shell tool UI.
