# RiftOS host documentation

This is the current host-only reference index, replacing historical embedded Core/Shell migration and testing narratives.

- [Architecture and APK code boundaries](ARCHITECTURE.md)
- [External component ABI, qualification, selection and manual Recovery](EXTERNAL_COMPONENTS.md)
- [MCP, Local Agent, virtual drives, RiftGit and workspace](OPERATING_SERVICES.md)
- [Generic RAPP/browser/tool/Android host services](PLATFORM_CAPABILITIES.md)
- [Manual host APK build, signing and valid host-only checks](HOST_VALIDATION.md)
- [Source ownership and external Core/Shell TODO separation](SOURCE_OWNERSHIP.md)

The frozen embedded Core/Shell is still compiled as compatibility/fallback by the current Gradle source inventory. No E0/E1/C1 embedded proof plans, old standalone test checklists or per-version TODOs are maintained in RiftOS. The selected external components and their tests live in RiftComponents.

**Source docs ≠ installed proof.** User-controlled Builder and device checks qualify host APK revisions; the independent Core/Shell Builder is a separate manual deployment process.
