# External Core / Shell host contract and recovery

## Ownership

Core and graphical Shell **implementation, TODOs, tests, releases and build recipes** belong to `/D:/Workspace/RiftComponents`. RiftOS owns only the stable Android ABI/IPC, component loading and artifact verification, trusted approval transport, durable selected/previous revisions, crash observation and manual Recovery surfaces.

Current observed device selection (2026-10-15): **Core 0.6.9 + Shell V10**, with V9 available as Shell Recovery backup. These are observations from `core status` and the graphical desktop, **not assertions that a newly pushed host APK has been compiled or installed**.

## Protected components

The host verifies declared component kind, ABI compatibility, bounded DEX, protected manifest format, entrypoint identity, content digest and accepted revision before selecting a component. Refer to source `RiftProtectedComponentManifest.kt`, `RiftProtectedDexVerifier.kt` and both candidate switches for the **authoritative executable contract**. No README may override the parser or relax signer/one-use approval requirements.

The Shell's host services provide installed RAPP enumeration, launch/stop, Core file-command forwarding and bounded results, app effect responses, activity-mediated APK install and optional external Android tool requests under Core policy. The APK must not absorb graphical window-management implementation or app-specific language logic.

## Manual-only deployment and recovery

1. Assistant or developer may edit source/recipes and run read-only audits.
2. **User** runs independent Core/Shell Builder RAPP and controls compile/preflight/sign/stage/install/selection.
3. Observe selected hashes/versions and exercise real device regression separately; static checks alone are insufficient.
4. Recovery restoration is a conscious user action; never silently promote a backup or erase a working selected component.

The embedded Core/Shell source and legacy graphical activities have been removed in this new host-only source candidate. No automatic embedded fallback is permitted. Device qualification is still pending a user-built and installed signed APK. Legacy E0/E1 plans remain retired and all external component TODOs belong to RiftComponents.
