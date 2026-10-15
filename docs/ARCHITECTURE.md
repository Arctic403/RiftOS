# Active RiftOS Android host architecture

## APK-owned Android surface

The checked-out app is `com.riftos.app`, with `RiftCoreApplication` as Application and `RiftShellActivity` in `:riftShell` as the graphical Activity/process envelope. The Android manifest also declares a separately launchable `RiftManualRecoveryActivity` (`:riftRecovery`), host Core supervisor, non-exported generic module service, browser preview Activity, authenticated IPC provider, MCP Activity, managed JVM/native build services and Local Agent Accessibility Service.

The selected independent Core implements the **Core component ABI** retained by `RiftHostComponentAbiV1.kt`; the selected independent Shell implements `RiftShellGraphicalComponentV1` and receives only the `RiftShellPlatformServicesV1` or additive `V2` host interface. APK wrappers implement Android lifecycle, class-loader and binder boundaries, package installation, native platform access and crash/recovery diagnostics.

## External-only source candidate

The APK no longer includes the embedded Core implementation (`RiftCoreRuntime`, app sessions/lifecycle/executor/package manager) or graphical Shell implementation (`RiftNativeDesktop`, embedded system/workspace windows, legacy RAPP renderer and proof Activities). `MainActivity` was removed. The selected external Core/Shell must already be qualified for the retained host interfaces; absent or invalid selection fails closed to manual Recovery.

`RiftNativeShell` is **not** the retired graphical Shell: it is the actual MCP command executor and remains host-owned. The Android Core-to-Shell window/consent IPC bridges, protected revision stores, binder transport, and generic RAPP capability helpers similarly remain APK-owned. **This is a source-only purge candidate until the user manually builds and verifies a signed host APK.**

## Major actively maintained host boundaries

- **External component verification/activation/recovery:** `RiftCoreCandidateSwitch`, `RiftShellCandidateSwitch`, `RiftProtectedComponentManifest`, `RiftProtectedDexVerifier`, `RiftProtectedComponentInstaller`, `RiftProtectedRevisionRecovery`, `RiftManualRecoveryStore`, `RiftManualRecoveryActivity`, `RiftCoreRecoverySupervisorService`.
- **Trusted IPC and shell adapters:** `RiftHostComponentAbiV1`, `RiftShellComponentAbiV1`, `RiftShellCoreClient`, `RiftCoreSurfaceIpcProvider`, `RiftHostExternalAppBinderV1`.
- **MCP, Local Agent, workspace:** `RiftMcpServer`, `RiftToolHost`, `RiftMcpRelayClient`, `RiftMcpOperationJournal`, `RiftVortexLocalAgent`, `RiftLocalAgentBatch`, `RiftVolumePaths`, `RiftWorkspaceRecords`, `RiftWorkspaceWatcher`.
- **Android generic capabilities:** `RiftRappCapabilityBroker`, `RiftManagedJvmToolService`, `RiftNativeBufferCompilerService`, `RiftBrowserPreviewActivity`, `RiftBrowserAndroidWebViewEngine`, `RiftSecretStore`.

This reference records ownership only, not a blanket claim that every optional service path was exercised on this device.
