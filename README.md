# RiftOS — Android host and external-component platform

RiftOS is the **signed Android host**, not the implementation repository for its independently loaded Core or graphical Shell. It owns process/bootstrap integration, authenticated host IPC, protected component qualification and recovery, Android package installation surfaces, RiftFS / workspace, generic RAPP/platform adapters, MCP/Local Agent tooling and Android permissions.

## Active architecture (device observation, 2026-10-15)

On the user's device, **external Core v0.6.9** and **graphical Shell V10** are selected and running. These runtime facts do not mean this checked-out Android host source has itself been newly built or installed. This **unbuilt source candidate** removes the APK-owned embedded Core and graphical Shell implementations and makes missing external components fail closed to manual Recovery. Existing installed APKs are unchanged until the user builds and installs a new host.

The Android APK is required for host/manifest/ABI/security/service changes. Compatible external Core and Shell DEX revisions are independently built, preflighted, packaged, verified and **manually staged/activated by the user** through the separate Component Builder RAPP. This repository must not contain external component sources, release recipes, or their TODOs.

## Current documentation

- [Architecture and APK boundaries](docs/ARCHITECTURE.md)
- [External component integration and recovery](docs/EXTERNAL_COMPONENTS.md)
- [MCP, Local Agent, RiftFS and workspace](docs/OPERATING_SERVICES.md)
- [RAPP, Android tool and browser host capabilities](docs/PLATFORM_CAPABILITIES.md)
- [Security, manual APK release and validation](docs/HOST_VALIDATION.md)
- [Source and documentation ownership](docs/SOURCE_OWNERSHIP.md)

## Source tree

- `android/` — application, manifest, Java/Kotlin, C++ and Gradle host.
- `docs/` — active host contracts and actual packaged capabilities only.
- `.riftgit.json` — local native RiftGit metadata.

Embedded Core/Shell implementation classes and legacy graphical proof Activities have been removed from the source and Gradle lists. The MCP command shell (`RiftNativeShell`) remains because it is a host tool, not a graphical Shell implementation. Historic code remains in RiftGit history only.

## Deployment authority

The **user** runs the RiftOS Builder manually to produce/install a signed APK and independently uses the Core & Shell Builder RAPP for Core/Shell revisions. Source updates, docs and audits do **not** imply installed-device proof. Neither an assistant nor CI silently promotes, installs or changes Recovery. No direct GitHub repo operation is part of local development: use RiftOS MCP + native RiftGit.
