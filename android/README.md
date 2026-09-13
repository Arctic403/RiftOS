# RiftOS Android Host Source

The `android/` tree builds the native RiftOS APK. The web shell is synchronized into generated assets at build time; Kotlin code owns Android framework authority, native rendering surfaces, storage providers, MCP native authority and platform services.

Start with [`../docs/systems/android-host/README.md`](../docs/systems/android-host/README.md), then use [`../docs/README.md`](../docs/README.md) to jump to the subsystem being changed. The Kotlin package has its own component index at `app/src/main/java/com/riftos/app/README.md`, and browser assets are indexed at `app/src/main/assets/README.md`.

Build/source synchronization is documented in [`../docs/systems/build-validation/README.md`](../docs/systems/build-validation/README.md).
