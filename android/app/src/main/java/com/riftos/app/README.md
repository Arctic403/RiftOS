# RiftOS Android application source ownership

This directory is the **signed Android platform host**. It is not a home for individual app, compiler, language, cloud worker, or external component source code.

## Generic runtime responsibilities

- Android lifecycle, desktop/embedded Shell surface, accessibility and input support.
- RiftFS filesystem and generic sandboxed app/RAPP lifecycle.
- External Core and graphical Shell component loaders and **manual Recovery**.
- Generic Binder transport (`RiftHostExternalAppBinderV1.kt`), authenticated IPC and signer validation.
- Generic signing/installation authorization and platform capabilities.
- Host diagnostics and recovery safeguards, including a few older `*Proof*` classes **still imported by live Recovery/admin paths**. These are not disposable tests.

## Pending decoupling (DO NOT DELETE until replaced)

The current Android host still contains **active application-specific adapters**:
- `RiftNativeWorkspaceApps.kt` has a Codynex Editor-specific App Connections form and Send label even though the external Core/host Binder interface is generic. Replace with a configurable generic external-app connection picker (same signer pin and user consent), then move Codynex-only UI into `Codynex-RiftOS-Bridge` if needed.
- `RiftLlmDevClient.kt` and `RiftTrainDataTaskRunner.kt`, with calls in `RiftNativeShellServices.kt`, `RiftNativeWorkspaceApps.kt`, and `RiftNativeShell.kt`, remain tied to the RiftLLM developer API. Replace those with a generic provider/RAPP gate before removing/moving actual implementations to `RiftLLM`.
- `RiftVortexBridgeClient.kt` and `RiftVortexLocalAgent.kt`, with callers in `RiftNativeShellServices.kt` and `RiftMcpRuntime.kt`, still implement Vortex-specific host bridges. These must migrate into the Vortex owner behind generic registered provider capabilities before deleting them.
- Rift++-specific RAPP interpreter/adapters (`RiftRappRiftppGenericAdapter.kt`, `RiftRappRiftppWs15Adapter.kt`) are used by the generic RAPP host today; replace the language-dependent adapter with independent RAPP/VM support before extraction.
- Old `*Proof*` component names may describe active rollback and admin paths. Follow references, don't delete by filename.

This is an explicit open architecture item; no claim of a fully project-neutral APK is made until the Android source has been refactored and a signed/device regression passes.

## External projects

- Independent Core/Shell DEX, compiler manifests and critical component builder: `/D:/Workspace/RiftComponents`.
- MCP Cloudflare Worker: `/D:/Workspace/RiftMcpRelay`.
- Old JavaScript/browser host, snapshots, legacy tests: `/D:/Workspace/RiftOS-LegacyWeb`.
- Apps, language compilers and proof fixtures belong in their respective `/D:/Workspace/<project>` directories.

The installed device remains Core v0.6.4 external, Shell embedded until user-built updates are manually qualified.
