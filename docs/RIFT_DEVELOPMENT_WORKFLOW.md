# RiftOS Development Workflow

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Device testing cadence — major-milestone acceptance (user decision 2026-10-09)

**Default new policy:** Do not require a separately installed Android APK for every small patch, small subgate, documentation correction or source-only checkpoint. During C1.4-C2 and later C1.4-C3/C1.5, use explicit validation tiers:

1. **Every source patch:** run focused source/contract regressions, security checks, dependency/source-ownership/docs validation and git diff. Fail closed on broken contracts. Report exactly which checks were *actually run*; source-marker inspection cannot be called executable regression or Kotlin compilation.
2. **Integration checkpoints:** group tightly coupled patches and run the full available repository checks and **compile/preflight checks through the existing Builder flow only when appropriate and explicitly user-dispatched**. No background automatic release, APK installation or assistant-dispatched Builder; reserve unproven Android behavior as BUILD/DEVICE PENDING.
3. **Major milestone signed-device acceptance:** after all **C1.4-C2-A/B** features are integrated, use one comprehensive user-manual signed build/install and C2 test matrix. Repeat per completed **C1.4-C3**, and **C1.5**, then final end-to-end acceptance. Record the exact installed source SHA, device evidence, Core/Shell/RAPP invariants, positive/negative permissions, recovery and rollback; intermediate source passes NEVER become DEVICE PASS.
4. **Risk-based exception:** test earlier on real Android when changing a boundary whose safety or feasibility requires actual hardware/OS proof—e.g., Binder authentication, process/recovery identity, native foreground lifecycle, Android PackageInstaller, actual provider binding, privileged filesystem effects, signing, or destructive rollback. Fail closed until proven; do not stack critical changes on a known unsupported assumption.

C2-A signer-stamped **EMPTY** runtime registry transaction is source-only, not an installed provider. C2-B1 read-only Android PackageManager candidate discovery is an integration checkpoint, not full C2 admission. C2-B2 actual signer-pinned registration and transactional restore is required before major **C2 DEVICE PASS**. Do not preemptively build a new RiftOS image just because a source checkpoint was pushed; user decides when to run Builder.

**Preserve authority boundaries:** production Core owns app execution and policy, Shell owns graphical UI, RAPPs never receive implicit admin, legacy build paths remain prohibited, and every external provider remains signer/identity checked. RiftOS-Dev.rapp is separately LOCKED for after C1.4 and C1.5 and must not bypass milestone proofs.

## Owner-first capability rule

If an active RiftOS subsystem is the canonical owner for a workflow and real development exposes a missing capability, extend that owner instead of routing around it through ad-hoc shell logic, MCP duplication, temporary files or another subsystem.

This applies especially to RiftGit, Dev Lab, Workspace Records, RiftFS, Project Intelligence, Local Agent, RiftBuild and MCP.

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

Do not recreate the deleted broad native dispatcher, trusted shell WebView, or obsolete CLI to shortcut ownership boundaries.

## Retired CLI boundary

RiftCLI has been permanently retired. Use the bounded RiftOS MCP / Local Agent engineering tools for AI-assisted workspace operations; do not recreate a parallel CLI runtime, driver or batch executor.

## Current AI-assisted change sequence

During Bootstrap-0, substantial AI-assisted repository work uses the existing RiftOS evidence/tools directly:

1. **Inspect ownership**
   - identify the canonical subsystem;
   - read code before docs when they conflict;
   - inspect callers, dependents, tests and build surfaces.

2. **Establish repository state**
   - check Git state;
   - identify the exact source revision when relevant;
   - use Workspace Records / Project Intelligence evidence where useful;
   - do not overwrite unrelated dirty work.

3. **Research uncertain assumptions**
   - use current authoritative sources when the decision depends on external facts;
   - distinguish evidence from inference;
   - do not encode unverified guesses into architecture.

4. **Plan by invariant**
   - define the architectural rule being changed;
   - determine every affected owner;
   - coordinate multi-file work conceptually, but do not collapse mutations into an opaque batch.

5. **Edit incrementally**
   - one explicit mutation at a time;
   - verify the result after each dependent change;
   - retain clear failure boundaries.

6. **Update documentation**
   - owner README;
   - source ownership;
   - architecture/status/roadmap when applicable;
   - patch history for substantial subsystem changes.

7. **Validate source**
   - focused regression tests;
   - repository validation;
   - security/dependency/documentation checks relevant to the change.

8. **Build**
   - native/package changes require the external Builder;
   - source checks do not count as Android compilation;
   - final APK checks must validate packaged native/runtime assets.

9. **Device/E2E**
   - install the exact user-built signed APK at a defined major milestone or earlier when a changed Android/security-critical boundary requires physical proof;
   - at interim source checkpoints, record DEVICE PENDING rather than demanding a reinstall for every small patch;
   - retest the milestone's original requirements plus negative/failure paths.

10. **Promote only with evidence**
   - distinguish source-validated, build-validated and device-proven states;
   - do not infer one from another.

Future MCP and Local Agent improvements must preserve the owner-first and incremental-mutation rules.

## Non-AI / small maintenance changes

A small manual source fix may use the same narrow safe sequence:

1. inspect source/owner/docs;
2. patch the smallest proven cause;
3. update owner README/validator if the contract changed;
4. source audit/diff;
5. user-only external Builder at integration or risk-triggered/major milestone gates;
6. signed-device validation at major milestones or where Android/security-critical risk requires it.

Do not falsely mark source-only checks as Android build/device proof.

## Push/promotion rules

- do not push local RiftOS changes without explicit project-owner instruction;
- source checks are not Android compilation;
- compiled Android changes require external Builder;
- install/live-abuse the exact built APK before calling runtime behavior device-proven;
- external AI reasoning does not promote trust by itself;

## Mutation rule

RiftOS engineering may **plan globally but must act incrementally**.

Coordinated multi-file architecture work is allowed and often required. The prohibited pattern is opaque multi-operation batch mutation that hides which operation failed or which intermediate state caused drift.

Every mutation should remain attributable, observable and independently verifiable.

## Migration-specific rules

- no WebKit imports outside the explicit RiftBrowser allowlist;
- only explicitly allowed headless JS assets are copied into the generated asset namespace;
- native shell/MCP must survive browser/Activity lifecycle changes;
- browser crash recovery is scoped to browser-owned renderers;
- source checks never count as a successful Android build;
