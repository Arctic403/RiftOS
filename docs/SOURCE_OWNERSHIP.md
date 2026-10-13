# Source ownership — Android host only

RiftOS-main is a platform implementation, not a monorepo for editor, language, AI, component, web, or build-provider projects.

| System | Source owner |
| --- | --- |
| Android app host, IPC, binder transport, RiftFS, installer, desktop/embedded Shell, Recovery | `/D:/Workspace/RiftOS-main/android` |
| External Core and optional graphical Shell source/recipes/component builder | `/D:/Workspace/RiftComponents` |
| Cloudflare remote MCP relay | `/D:/Workspace/RiftMcpRelay` |
| Legacy web prototype and historical Node contract tests | `/D:/Workspace/RiftOS-LegacyWeb` |
| Hosted compile/pack/sign orchestrator | `/D:/Workspace/RiftBuild-Hosted` |
| Rift++ language and editor tooling | `/D:/Workspace/rift++` |
| Codynex editor/compiler/VM and Android bridge | `/D:/Workspace/Codynex`, `/D:/Workspace/Codynex-RiftOS-Bridge` |
| RiftLLM application and specialist integrations | `/D:/Workspace/RiftLLM` |
| Semnexis compiler, fixtures and proof programs | `/D:/Workspace/Semnexis` |
| Vortex agent/tool documentation and engine | `/D:/Workspace/Vortex3d` |

## Rules

- **Only reusable host capabilities stay in RiftOS**. Component code belongs to an independently compiled project, not packaged in the host.
- Independent component builder uses project `/D:/Workspace/RiftComponents` and publishes manually staged DEX candidates to `/D:/Builds/Components`. APK host does not automatically select them.
- Cloudflare relay and older web prototype are separate and not part of the Android source set.
- Generic host tests belong with Android; project-specific proof/tests belong to their project. Historical Node tests have been moved out and are not assumed to pass unchanged.
- Existing Codynex App Connections UI, RiftLLM developer/training functions, Vortex agent/bridge client, and Rift++ interpreter/RAPP adapters still have **live project-specific Android implementations**. They cannot be deleted until generic transport/provider replacement and signed-APK regression testing preserve the installed workflows.
- Previously proved Core v0.6.4 / Recovery v0.6.3 and the current embedded Shell must be preserved until replacement candidates are signed and device-proven.
- No direct GitHub import or push for Codynex. Local RiftGit rules remain in force.

## 2026-10-12 cleanup

Moved external components, MCP relay, JavaScript legacy source and tests, Rift++ samples, Semnexis test fixtures, and project-specific docs to their owner workspaces. Deleted bootstrap proof source/workflow, empty proofs/evidence/examples/apps folders, retired RiftCLI docs and superseded device-test proof files. The root README and roadmap now describe the live host rather than ancient proof history.

**Build qualification is still pending.** The copy of the Android source tree was not rebuilt or installed by this source cleanup.
