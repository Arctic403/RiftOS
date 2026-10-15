# APK-owned platform capabilities

The Android host supplies generic, permission-bound services consumed by the selected independent Core/Shell and RAPPs. This source candidate excludes the embedded Core and graphical Shell implementations entirely; the live APK remains unchanged until the user's next manual build/install.

## Current source-owned surfaces

- **RAPP platform boundary:** `RiftAppAbi.kt`, `RiftRappCapabilityBroker.kt`, generic adapters, external runtime providers, executable host bridges and Android input/surface plumbing. The independently selected Core owns app-session admission, focus, grants and execution decisions.
- **Android build/install services:** `RiftBuildManagedToolchains`, `RiftManagedJvmToolService`, `RiftNativeBufferCompilerService`, FileProvider, Android PackageInstaller bridge. These are reusable services; component build recipes and standalone Editor/compiler toolchains live elsewhere.
- **Browser WebView host:** `RiftBrowserAndroidWebViewEngine`, `RiftBrowserPreviewActivity`, renderer crash guard and associated assets. Any optional browser UX is subordinate to current Android WebView ownership; no obsolete JavaScript desktop is bundled as the RiftOS UI.
- **User and service integration:** `RiftRelaySettings`, `RiftSecretStore`, `RiftDebugHub`, `RiftAppDiagnosticBridge`, `RiftVortexBridgeClient` and generic external Binder service interfaces.

## Explicit non-ownership

- External Core/Shell TODOs and regression suites: `RiftComponents`.
- Rift++ Editor/toolchain, Codynex, Semnexis, Vortex3D, LLM applications: each owning project.
- Hosted RiftBuild and standalone Android compile service: their project repositories.
- Cloudflare MCP relay code: `RiftMcpRelay`.

An Android class still required by Gradle is not necessarily a recommended API or a proof of active device use. Only current live Core/Shell selection and direct device tests qualify specific behavior.
