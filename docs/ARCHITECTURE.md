# Active RiftOS Android host architecture

## APK-owned Android surface

The checked-out app is `com.riftos.app`, with `RiftCoreApplication` as Application and `RiftShellActivity` in `:riftShell` as the graphical Activity/process envelope. The Android manifest also declares a separately launchable `RiftManualRecoveryActivity` (`:riftRecovery`), host Core supervisor, non-exported generic module service, browser preview Activity, authenticated IPC provider, MCP Activity, managed JVM/native build services and Local Agent Accessibility Service.

The selected independent Core implements the **Core component ABI** retained by `RiftHostComponentAbiV1.kt`; the selected independent Shell implements `RiftShellGraphicalComponentV1` and receives only the `RiftShellPlatformServicesV1` or additive `V2` host interface. APK wrappers implement Android lifecycle, class-loader and binder boundaries, package installation, native platform access and crash/recovery diagnostics.

## APK code still present but frozen

The Gradle mandatory Kotlin source inventory still contains `RiftNativeDesktop.kt`, `RiftNativeShell.kt`, `RiftNativeWorkspaceApps.kt`, `RiftCoreRuntime.kt`, `RiftCoreAppExecutor.kt`, etc. **Presence in APK does not establish active ownership.** Do not extend the embedded Core/Shell or treat their historical milestones as open gates. Compatibility/fallback removal, if ever authorized, is a separate host migration requiring source-closure analysis, signed build and explicit device/recovery proof; it is not accomplished by removing docs/tests.

`MainActivity.kt` still references embedded desktop presentation for compatibility. Do not assert that all embedded implementation classes have already been deleted, and do not remove dependencies blindly.

## Major actively maintained host boundaries

- **External component verification/activation/recovery:** `RiftCoreCandidateSwitch`, `RiftShellCandidateSwitch`, `RiftProtectedComponentManifest`, `RiftProtectedDexVerifier`, `RiftProtectedComponentInstaller`, `RiftProtectedRevisionRecovery`, `RiftManualRecoveryStore`, `RiftManualRecoveryActivity`, `RiftCoreRecoverySupervisorService`.
- **Trusted IPC and shell adapters:** `RiftHostComponentAbiV1`, `RiftShellComponentAbiV1`, `RiftShellCoreClient`, `RiftCoreSurfaceIpcProvider`, `RiftHostExternalAppBinderV1`.
- **MCP, Local Agent, workspace:** `RiftMcpServer`, `RiftToolHost`, `RiftMcpRelayClient`, `RiftMcpOperationJournal`, `RiftVortexLocalAgent`, `RiftLocalAgentBatch`, `RiftVolumePaths`, `RiftWorkspaceRecords`, `RiftWorkspaceWatcher`.
- **Android generic capabilities:** `RiftRappCapabilityBroker`, `RiftBuildPlatformTools`, `RiftManagedJvmToolService`, `RiftNativeBufferCompilerService`, `RiftBrowserPreviewActivity`, `RiftBrowserAndroidWebViewEngine`, `RiftSecretStore`.

This reference records ownership only, not a blanket claim that every optional service path was exercised on this device.
