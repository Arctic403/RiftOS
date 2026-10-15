# Android platform host

The Android Gradle project builds the signed generic RiftOS APK. Production entrypoints, IPC, Accessibility, MCP/relay, RiftFS, selected external component loaders, manual Recovery, Android tooling and browser adapters are under `app/`; the companion `rift-managed-kotlin-tool` module supplies host-registered managed compiler adapter dependencies.

Current selected Core/Shell implementation is externally packaged; existing embedded Core/Shell classes compiled by Gradle are frozen compatibility/fallback. Do not add embedded Shell features, embedded Core TODOs or legacy validation gates here.

See [active host architecture](../docs/ARCHITECTURE.md) and [host release validation](../docs/HOST_VALIDATION.md). The user runs Riftos-builder manually; this module does not auto-install a host APK or activate external components.
