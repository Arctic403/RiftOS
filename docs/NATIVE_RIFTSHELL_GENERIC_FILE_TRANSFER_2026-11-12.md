# Native RiftShell generic tool-file transfer

Date: 2026-11-12

## Problem
External graphical RiftShell V16 already supported Core's signer-pinned generic file transfer via the Core's `tool-file-send` and `tool-file-receive` requests. But the native RiftShell executed by the installed RiftOS MCP `rift_shell_exec` tool accepted only `tool list`, `tool call` and `tool trust`. Thus an AI controlling RiftOS via MCP could not import large Blockbench .bbmodel files without touching the graphical terminal.

## Change
`android/app/src/main/java/com/riftos/app/RiftNativeShell.kt`: add the following commands to the existing `genericToolCommand` parser:

- `tool file import <approved-id> <RiftFS-source>`: forwards `tool-file-send` with exact `id` and `from`.
- `tool file export <approved-id> <artifact> <RiftFS-destination>`: forwards `tool-file-receive`.
- `tool file status`: forwards `tool-file-status`.
- `tool file cancel`: forwards `tool-file-cancel`.

All requests use the exact existing `riftos.core.app-connection-request/1` schema and `RiftHostCoreComponents.externalAppConnections().appConnectionRequest` endpoint. Independent Core remains solely responsible for saved-scope enforcement, provider signer pinning, user consent, RiftFS canonical path protection, digest checks, and the asynchronous 4096-byte Binder transport. This change does not add a new permission, local listener, bridge APK, or endpoint, nor does it bypass approval.

The native command validates IDs, artifact names and public RiftFS path syntax before forwarding, using the same syntax as external graphical Shell V16.

## Blockbench/RiftElectricity smoke test after user installs the updated RiftOS host APK

Preconditions:
- Installed Blockbench Mobile 0.2.8 (integrated RiftOS bridge).
- Saved `blockbench-mobile` Generic Tool Connection has `file-import` and `imports_status` scopes in addition to original operations.
- Blockbench bridge running/paired.
- User personally rebuilds, signs and installs RiftOS from the GitHub repo. **The running APK is not updated by editing this source file.**

Via MCP native `rift_shell_exec`, one model at a time:

```text
tool file import blockbench-mobile /D:/Workspace/RiftElectricity/Model_Optimization_v0_2_16/models/RiftElectricity_MANUAL_Original_V1_OPT_v016.bbmodel
tool file status
tool file import blockbench-mobile /D:/Workspace/RiftElectricity/Model_Optimization_v0_2_16/models/RiftElectricity_OR_Either_Input_Relay_V2_OPT_v016.bbmodel
tool file status
tool file import blockbench-mobile /D:/Workspace/RiftElectricity/Model_Optimization_v0_2_16/models/RiftElectricity_AND_Dual_Enable_Relay_V2_1_OPT_v016.bbmodel
tool file status
tool file import blockbench-mobile /D:/Workspace/RiftElectricity/Model_Optimization_v0_2_16/models/RiftElectricity_XOR_Exclusive_Input_Relay_V2_2_Wired_OPT_v016.bbmodel
tool file status
tool file import blockbench-mobile /D:/Workspace/RiftElectricity/Model_Optimization_v0_2_16/models/RiftElectricity_RiftDivider_T3_Three_Way_Power_Divider_V1_OPT_v016.bbmodel
tool file status
```

Check `imports_status` through `tool call blockbench-mobile /D:/Workspace/BlockbenchAndroid/android/bridge-tests/imports-status.json`. Expect five verified staged models. Foreground Blockbench, then use `RiftOS: Open Staged Models in Tabs` once. A direct MCP `submit` command with action `open_next_import` can also open one model per call while Blockbench is foregrounded.

## Verification/rollback

- Compile the updated Android host; source-only audit is not a Kotlin compilation test.
- Check approved scopes with `tool list`, submit first model, wait until `tool file status` reports completion and check import queue. Repeat for five.
- Negative tests: denied consent, wrong tool ID, forbidden RiftFS paths, symlinks, missing model, oversized model, Core job cancellation.
- No changes to existing Core/Shell payloads or Blockbench APK are required by this patch.
- Revert only this single host parser change if native transfer commands fail. Model source files and RiftElectricity releases remain unchanged.
