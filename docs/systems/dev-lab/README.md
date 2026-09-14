# RiftOS Dev Lab

## Purpose

RiftOS Dev Lab is the trusted in-house development workspace for changing and abusing the running OS without writing experiments directly into the canonical project. It mirrors the Vortex3D Dev Lab model: experiment first, freeze a known-good snapshot with evidence, then publish the approved source delta into the local project. The installed APK is never silently rewritten.

The Dev Lab stores its private state under `/system/devlab`. The canonical source target remains `/workspace/RiftOS-main`. Staging, live experimentation and evidence capture happen outside the project tree; only **Publish snapshot** may mutate the project, and publication must go through RiftWorkspace's guarded atomic patch path.

## Source ownership

- `src/riftdevlab.js` — state/provenance, source staging, live CSS overrides, trusted Live Script runner, evidence journals, snapshots, guarded publication and the built-in Dev Lab UI.
- `src/riftcore.js` — registers Dev Lab as a trusted built-in system app with filesystem permissions.
- `src/riftos.js` — launcher/open-app route only; Dev Lab implementation does not live in the desktop manager.
- `src/riftworkspace-web.js` — existing patch preview/apply/history/rollback transaction authority used by Dev Lab publication.
- `src/riftandroid-entry.js` — imports the Dev Lab after Workspace/Git are available and before desktop launch routing is used.

## Storage and provenance

Private lab data:

- `/system/devlab/state.json` — current staging manifest, baseline Git head, latest snapshot/publication pointers.
- `/system/devlab/stage/**` — staged source contents. These are not project files.
- `/system/devlab/snapshots/*.json` — frozen source snapshots containing exact staged contents, per-file `base_sha256`, baseline/captured Git head and recent run evidence references.
- `/system/devlab/runs/*.json` — Live Script evidence journals.
- `/system/devlab/publications/*.json` — publication receipts that point at the RiftWorkspace history id created by the atomic project patch.

The attached RiftGit metadata at `RiftOS-main/.riftgit.json` supplies the repository `headSha`. More importantly, every staged project file captures a SHA-256 of its exact workspace baseline. A later publication uses those hashes as RiftWorkspace v2 `base_sha256` guards. If any project target has changed since staging/snapshot, patch preview fails and **nothing is published**.

## Live experimentation contract

Source staging is separate from runtime execution:

- CSS source can be applied as a reversible in-document `<style>` override and removed without touching the project or APK.
- JavaScript behavior can be prototyped with **Live Script**, an async trusted development script surface receiving read-only `core`/`workspace` views, runtime `desktop`/`windowManager` controls and a bounded helper object (`lab.log`, `lab.assert`, `lab.sleep`, `lab.status`, `lab.staged`). Runs are journaled as evidence. The default script API deliberately omits filesystem/workspace write methods so source mutation still flows through stage/snapshot/publish.
- JavaScript source files can still be staged for publication, but Dev Lab does not pretend re-executing arbitrary boot modules is equivalent to replacing their existing lexical state/listeners.
- HTML, Kotlin/Java, Android manifest/XML, Gradle and native C/C++ source are staging-only in the running APK and are explicitly classified as rebuild/reload-required.

All source publications still require a later APK build/install before the installed package permanently uses the changed files. Dev Lab reduces rebuild iteration by letting runtime behavior/CSS be tested and evidence collected first; it does not claim compiled Android code changed live.

## Snapshot and publication flow

1. Load a project-relative text source path.
2. Stage an edit or deletion. The first stage captures the current RiftGit head plus the exact file baseline hash.
3. Apply reversible live CSS overrides and/or use Live Script to abuse the current runtime; run evidence is retained.
4. Create a snapshot. Snapshot content is immutable and independent of later editor changes. Independent staged-file and evidence reads are queued together so large multi-file snapshots do not pay one WebView/native round trip at a time; ordering, copied bytes and hash guards remain unchanged.
5. Preview publication. Dev Lab converts the snapshot into one `riftcity-ai-patch` v2 patch targeting `RiftOS-main/**` with `base_sha256` on every source.
6. Publish. RiftWorkspace validates every baseline, snapshots affected paths, applies the entire patch, creates one rollback history entry, and restores all affected files if any write fails.
7. Dev Lab stores a publication receipt and clears only staged entries that still exactly match the published snapshot. Newer edits remain staged.

Snapshots and run evidence survive `Reset staged` so a failed experiment can be discarded without erasing the evidence trail.

RiftShell exposes the same workflow for automated abuse: `devlab status`, `staged`, `stage`, `stage-file`, `delete`, `unstage`, `css`, `css-off`, `run`, `run-file`, `snapshot`, `snapshots`, `preview`, `publish`, `reset`, and `open`. `latest` resolves the newest snapshot for preview/publish. The `devlab` command family is explicitly rejected inside RiftShell atomic `batch`; Dev Lab publication already has its own guarded Workspace transaction and live/runtime actions are not reversible filesystem batch operations.

### RiftFS source-path compatibility

`stage-file` / `run-file` source arguments use `RiftOSCore.path.isAbsolute()`, so bare `C:/...` and `D:/...` inputs are treated as absolute RiftFS paths. This changes only where Dev Lab reads an exact staging payload from; project publication remains project-relative and guarded through RiftWorkspace.

### Local-agent tool layer

`riftos-agent devlab ...` is the automation-safe controller intended for ChatGPT/MCP acceptance work. Shell parsing produces a structured request (`action`, paths/content/snapshot id, cwd); native `RiftDevLabLocalAgent` accepts only the fixed Dev Lab action set, encodes the request as base64 JSON, and sends only `devlab rpc <payload>` through the current authoritative `RiftShellBridge`. The Dev Lab runtime then calls `executeAgentRequest()` and therefore uses the same staging metadata, per-file baselines, evidence, immutable snapshots and guarded Workspace publication as the UI/direct `devlab` shell family. There is no arbitrary command passthrough and no direct stage-folder mutation. `stage-file` and `run-file` are the preferred exact-payload paths for multi-line or heavily quoted code.

The native dispatcher runs agent calls on a dedicated worker so this synchronous agent-to-shell round trip cannot block the ordinary native worker that nested RiftFS/workspace operations require.

## Failure signatures

- **Publish aborted: Workspace file changed since the patch was created** -> the canonical project drifted after staging/snapshot. Reload/re-stage against the current project; never bypass the hash guard.
- Source editor shows missing/empty unexpectedly -> verify the path is project-relative and exists under `RiftOS-main`; binary/directory editing is intentionally unsupported.
- Live CSS appears wrong after restaging -> re-apply the CSS override; staging and live application are intentionally separate actions.
- Live Script throws -> inspect the run journal/output; scripts use trusted shell APIs but do not gain raw Android shell/ADB authority.
- Native/Kotlin/manifest edit does not change the running APK -> expected. It is staged source and requires the next Android build/install.
- Publish succeeds but Git is dirty -> expected. Dev Lab publishes to the **local workspace**, not GitHub. Review Workspace Records/diff, update the project patch ledger, then push/build according to project policy.

## Fix map

Staging/snapshot/live-script/publication behavior -> `src/riftdevlab.js`.
Atomic conflict detection/rollback/history -> `src/riftworkspace-web.js`; do not duplicate it in Dev Lab.
Launcher/window routing -> `src/riftos.js`.
Trusted app permissions -> `src/riftcore.js`.
Load order -> `src/riftandroid-entry.js`.
Source/build checks -> `scripts/test-rift-dev-lab.mjs`, `scripts/validate-rift-transport.mjs`, `scripts/validate-rift-wiring.mjs`.

## Validation

Source validation must prove that the Dev Lab is imported, registered as a built-in app, targets `/system/devlab` for experiments, targets only `RiftOS-main/**` for publication, emits Workspace patch v2 changes with `base_sha256`, previews before applying, and does not directly write project source through raw RiftFS.

The focused test must prove that staging leaves the project unchanged, snapshots freeze exact staged content, later project drift causes publication to fail before mutation, successful publication uses the guarded Workspace patch, and reset clears only active staging while retained snapshots/runs remain available.

On device, abuse source staging, CSS hot apply/remove, Live Script pass/fail evidence, snapshot creation, post-snapshot restaging, conflict abort, successful publish, Workspace Records visibility, rollback, and the next manual APK build. Compiled-source changes must never be reported as live until that APK is installed.
