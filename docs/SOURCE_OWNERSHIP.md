# Source ownership — active RiftOS host

| Scope | Owner |
| --- | --- |
| Android lifecycle, permissions, ABI, external DEX loading, host binder services, signed APK Recovery | `/D:/Workspace/RiftOS-main/android` |
| MCP server/client runtime, Local Agent UI access, tools, RiftFS and workspace | `/D:/Workspace/RiftOS-main/android` |
| Independent Core/Shell code, regression tests, features, TODOs, versioned DEX recipes, component Builder | `/D:/Workspace/RiftComponents` |
| Standalone host APK Builder workflow and final signed package verifier | `/D:/Workspace/Riftos-builder-main` |
| Hosted build/compile app | `/D:/Workspace/RiftBuild-Hosted` |
| Cloudflare MCP relay implementation | `/D:/Workspace/RiftMcpRelay` |
| Rift++/Codynex/Semnexis/Vortex/LLM components | Their respective project workspaces |

## Rules

The embedded Core/Shell classes that still compile into the Android host are **frozen compatibility/fallback**, not development owners. Their old docs and test-validation gates were retired. No Core/Shell TODOs or enhancement tracking belongs in this repo. Keep functional owner-specific docs/tests in `RiftComponents`, never relabel them as an APK requirement.

Android permission, signer, IPC, recovery, runtime/tool API and signed artifact checks remain host responsibilities. Use the RiftOS MCP and native RiftGit for edits/pushes. The user alone triggers signed APK builds and independent component installation/activation.
