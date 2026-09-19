# RiftOS Development Workflow

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

## Owner-first capability rule

If an active RiftOS subsystem is the canonical owner for a workflow and real development exposes a missing capability, extend that owner instead of routing around it through ad-hoc shell logic, MCP duplication, temporary files or another subsystem. This applies especially to RiftGit, Dev Lab, Workspace Records, RiftFS, Project Intelligence and the Local Agent.

New capability still needs a real caller/use-case, bounded authority, documentation and regression coverage. Do not add dead APIs merely for surface parity with desktop tools.

## Current source ownership rule

Patch the narrow owner:
- desktop/window behavior -> `RiftNativeDesktop.kt`;
- built-ins -> `RiftNativeSystemApps.kt` / `RiftNativeWorkspaceApps.kt`;
- shell -> `RiftNativeShell.kt` / `RiftNativeShellServices.kt`;
- Git -> `RiftNativeGit.kt`;
- workspace MCP -> `RiftToolSandbox.kt`;
- MCP catalog/server -> `RiftToolHost.kt` / `RiftMcpServer.kt`;
- browser/app/preview rendering -> explicit `RiftBrowser*` source;
- Rift++ compiler/VM -> `src/riftpp-core.js`, `src/riftvm.js`, `RiftHeadlessJsRuntime.kt`;
- manual AI patch lifecycle -> `RiftCliPatchLifecycleV1.kt` + `RiftResearchLedgerV1.kt`.

Do not recreate the deleted broad native dispatcher or trusted shell WebView to shortcut ownership boundaries.

## Canonical AI-assisted change sequence

For substantial AI-assisted repository work, use the Experimental RiftCLI lifecycle contract:

1. **Acquire**
   - manually enable Experimental RiftCLI;
   - use `begin-sync` when the intent is to start from remote main/latest branch state;
   - require clean Git state;
   - bind Git HEAD + Project Export snapshot + bounded full-repo inventory;
   - refuse a global checkpoint if unrelated Workspace changes exist.
2. **Understand**
   - map repository structure and subsystem ownership;
   - trace APIs/imports/references/dependents;
   - read tests/build/CI/dependency manifests;
   - read README/docs/ROADMAP/TODO/TASK/patch-history/source-ownership surfaces that actually exist;
   - import complete base-bound understanding evidence before research.
3. **Research**
   - verify external assumptions before patching;
   - record source/version/retrieval/claim evidence;
   - prefer authoritative sources for critical claims.
4. **Document intent**
   - describe implementation, docs, tests, compatibility, security, risks and rollback before code mutation.
5. **Patch**
   - use narrow guarded edits/transactions through existing tools;
   - never silently edit unrelated/generated/vendor surfaces.
6. **Audit documents first**
   - request the exact lifecycle `documentation-plan` after patching;
   - source -> owner docs/README/TODO/ROADMAP/status/ownership/history;
   - import exact Patch-8 source/governance review evidence using the plan SHA;
   - repair drift and then regenerate documentation evidence because any candidate change makes the prior parity plan stale.
7. **Audit code**
   - changed source + callers/dependents/references/imports;
   - duplicate/dead/error/bounds/concurrency/compatibility review.
8. **Audit security/supply chain**
   - capabilities/secrets/permissions;
   - lockfile/dependency/SBOM/license/provenance evidence when applicable.
9. **Test / build evidence**
   - impact-derived focused tests are required for source/build-config changes;
   - run any local/source validation available before evaluation;
   - external artifact Builder evidence is optional in Lifecycle V1 until Patch 13 can bind an accepted candidate to Builder input safely;
   - when build evidence is supplied, capture builder/toolchain/source revision and artifact digests.
10. **End-to-end verify**
    - retest the original request and negative/failure paths;
    - verify no unexplained files or adjacent regressions;
    - document rollback.
11. **Freeze**
    - freeze Patch Manifest V1 candidate;
    - bind semantic impact + evidence bundle.
12. **Independent AI evaluation**
    - send the evaluation packet, not a self-authored completion claim;
    - require exact structured defects or an acceptable-candidate response.
13. **Local verify**
    - re-check echoed hashes and evaluator/patch-actor separation;
    - OBSERVE only returns WOULD_ACCEPT/WOULD_DENY.

See:
- `docs/RIFT_AI_PATCH_PIPELINE.md`;
- `docs/systems/experimental-cli/PATCH_LIFECYCLE_V1.md`.

## Non-AI / small maintenance changes

A small manual source fix may still use the narrow safe sequence:
1. inspect source/owner/docs;
2. snapshot/guard;
3. patch;
4. update owner README/validator;
5. source audit/diff;
6. external Builder when native/package behavior changes;
7. device abuse where runtime behavior changed.

Do not falsely mark source-only checks as Android build/device proof.

## Push/promotion rules

- do not push local RiftOS changes without explicit project-owner instruction;
- source checks are not Android compilation;
- compiled Android changes require external Builder;
- install/live-abuse the exact built APK before calling runtime behavior device-proven;
- ENFORCE is not enabled by Lifecycle V1;
- AI evaluation never promotes `trustedCheckpoint`;
- external Builder provenance handshake remains later roadmap work.

## Migration-specific rules

- no WebKit imports outside the explicit RiftBrowser allowlist;
- only Rift++ Core/VM JS assets are copied into the generated headless asset namespace;
- native shell/MCP must survive browser/Activity lifecycle changes;
- browser crash recovery is scoped to browser-owned renderers;
- source checks never count as a successful Android build.
