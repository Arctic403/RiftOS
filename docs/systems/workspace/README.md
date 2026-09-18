# RiftWorkspace

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

## Purpose

RiftWorkspace is the canonical app-private project tree shared by RiftOS development tools.

Physical root:

`<filesDir>/riftfs/workspace`

Display alias:

`/D:/Workspace`

MCP path form:

`workspace/...`

Those names resolve to the same app-private workspace tree; they are not separate copies.

## Source ownership

Core live owners:
- `RiftToolSandbox.kt` — workspace-only MCP filesystem, Code Mode and Project Intelligence.
- `RiftNativeWorkspaceApps.kt` — native Files/Editor UI that can browse/edit RiftFS including D:/Workspace.
- `RiftWorkspaceWatcher.kt` — recursive observer of the physical workspace.
- `RiftWorkspaceRecords.kt` — private change/checkpoint store outside workspace.
- `RiftDiffEngineV2.kt` — deterministic bounded multi-hunk text diff engine consumed by Workspace Records.
- `RiftFileIdentityV2.kt` — deterministic bounded structural identity evidence for rename/copy/rewrite correlation.
- `RiftNativeGit.kt` — Git/project synchronization, including `/workspace/RiftOS-main`.
- `RiftNativeDevLab.kt` — staged development/publish flow targeting the RiftOS workspace project.
- `RiftNativeShell.kt`, `RiftHeadlessJsRuntime.kt`, and native shell services — additional bounded app-private writers where their command/capability allows workspace paths.

Path alias ownership:
- `RiftVolumePaths.kt` maps D:/Workspace to physical `riftfs/workspace`.

## Retained/non-live sources

Retained reference/migration sources:
- `src/riftworkspace-web.js`
- `src/riftworkspace-android-adapter.js`
- `src/riftworkspace-live-host.js`
- `workspace-live/*`
- old shell/desktop JS that references workspace DOM or old native-call routes.

Current Android Gradle packaging does not include those workspace web modules or `workspace-live/`.

Their presence in the repository does not create a live web workspace runtime.

## Canonical authority model

There is one canonical workspace tree, but no single writer owns all mutations.

Different native subsystems have narrower responsibilities:
- MCP/Code Mode — workspace-only bounded tool execution.
- Native Editor — user-directed text editing.
- Git — repository/project synchronization.
- Dev Lab — staged changes and publish.
- Shell/headless runtime — command/runtime-specific app-private operations.

The filesystem remains source of truth.

Workspace Records observes and records changes; it does not become mutation authority.

## Native Editor behavior

The native Editor opens internal RiftFS files through the same app-private root mapping.

Internal text editing is bounded to 1 MiB.

Internal save writes through a temporary file in the same parent and replaces the target only after writing the staged bytes.

Editor support for SAF `/Android` files is a separate Files/SAF capability and is not part of the workspace tree.

## MCP boundary

MCP does not receive all RiftFS authority.

`RiftToolSandbox` canonicalizes tool access to physical `riftfs/workspace` only.

C:/, D:/Documents, SAF /Android, secrets, records state and sibling RiftFS roots are not addressable through normal workspace tools.

## Watcher/records relationship

`RiftWorkspaceWatcher` recursively watches the physical workspace root directly.

This means changes from any local writer can become visible to `RiftWorkspaceRecords`, not only MCP-originated changes.

MainActivity owns watcher lifecycle for the live UI process.

The watcher event sink supplied by the current Activity is not the old retained HTML Workspace surface.

## Records storage separation

Workspace Records state is stored under app-private:

`<filesDir>/rift-workspace-records`

not under `riftfs/workspace`.

This prevents normal workspace tools/projects from rewriting their own record/checkpoint store.

Records semantics are audited separately in the Workspace Records subsystem.

## Git workspace project

Native Git has a special workspace target:

`/workspace/RiftOS-main`

which corresponds to physical:

`riftfs/workspace/RiftOS-main`.

Git operations do not redefine the workspace root.

Git checkpoints can notify Workspace Records after applicable workspace operations.

## Dev Lab relationship

Native Dev Lab targets `workspace/RiftOS-main` as its project root while staging work separately under RiftFS system Dev Lab state.

Dev Lab does not create an alternate workspace filesystem.

Its transaction/publish semantics are audited in the Dev Lab subsystem.

## No current web workspace API

No source-proven active Android path currently exposes the old browser-hosted `RiftWorkspaceJSON`, shadow-root Workspace dashboard, web snapshot API, or old JavaScript patch/history authority.

If such behavior is wanted again it must be rebuilt against current native owners rather than assumed from retained JS.

## Non-ownership boundaries

Workspace does not own:
- C:/D: alias definitions -> RiftFS;
- MCP permission policy -> Tool Host;
- Code Mode transaction internals -> Sandbox;
- persistent records/checkpoint semantics -> Workspace Records;
- Git remote transactions -> RiftGit;
- Dev Lab staging/publish -> Dev Lab;
- SAF /Android -> Files.

## Critical invariants

- one canonical physical workspace root;
- D:/Workspace and MCP workspace resolve to that root;
- normal MCP tools cannot escape workspace;
- Workspace Records state stays outside workspace;
- watcher observes the canonical tree rather than a shadow copy;
- structural identity evidence stays observational, bounded and explicit about exact versus heuristic matches;
- Git/Dev Lab target workspace subtrees without redefining root;
- retained workspace web code is not treated as packaged/live.

## Failure signatures

- MCP and native Files resolve D:/Workspace to different physical directories -> root identity regression;
- workspace records appear under the workspace tree -> audit-integrity regression;
- watcher observes a legacy sandbox root -> observation regression;
- old `workspace-live/` HTML is claimed as live built-in -> documentation/runtime drift;
- a retained `core.native.call("workspace...")` route is treated as current authority without native caller/receiver proof -> stale-runtime regression.

## Fix map

Root alias -> `RiftVolumePaths.kt`.

MCP/Code Mode -> `RiftToolSandbox.kt`.

Native Editor/Files -> `RiftNativeWorkspaceApps.kt`.

Observation/records -> `RiftWorkspaceWatcher.kt`, `RiftWorkspaceRecords.kt`.

Text diff computation -> `RiftDiffEngineV2.kt`.

File identity correlation -> `RiftFileIdentityV2.kt`.

Git -> `RiftNativeGit.kt`.

Dev Lab -> `RiftNativeDevLab.kt`.

## Validation

Source verification must recheck:
- exact physical root identity;
- D:/Workspace alias;
- MCP containment;
- all native workspace writer families;
- native Editor temp-write path and bound;
- watcher root;
- records root separation;
- Git/Dev Lab project roots;
- Gradle/package absence of retained workspace web modules.

Installed-device proof still requires live writer/watcher/editor/Git/Dev Lab tests.
