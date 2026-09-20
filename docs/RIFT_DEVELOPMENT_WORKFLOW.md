# RiftOS Development Workflow

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Owner-first capability rule

If an active RiftOS subsystem is the canonical owner for a workflow and real development exposes a missing capability, extend that owner instead of routing around it through ad-hoc shell logic, MCP duplication, temporary files or another subsystem.

This applies especially to RiftGit, Dev Lab, Workspace Records, RiftFS, Project Intelligence, Local Agent, RiftBuild and RiftCLI.

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
- RiftCLI -> `android/app/src/main/cpp/riftcli/` with `RiftCliHost.kt` as thin JNI host only.

Do not recreate the deleted broad native dispatcher, trusted shell WebView, or retired Kotlin Experimental RiftCLI to shortcut ownership boundaries.

## RiftCLI reset rule

The former Experimental RiftCLI lifecycle/swarm/IR stack was retired on 2026-09-19.

Current RiftCLI is Bootstrap-0:

- C++ native core;
- Kotlin JNI transport host;
- ARM64 primary + ARM32 compatibility;
- process-local explicit enable only;
- no model/API backend;
- no project memory yet;
- no planner yet;
- no mutation/tool/network authority.

See `docs/systems/riftcli/README.md`.

Until the native engineering gates are promoted, do **not** pretend the old lifecycle still exists or route around the missing native features.

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
   - install the exact built APK when runtime behavior changed;
   - retest the original request plus negative/failure paths.

10. **Promote only with evidence**
   - distinguish source-validated, build-validated and device-proven states;
   - do not infer one from another.

Future native RiftCLI gates will automate and harden this sequence without changing the owner-first or incremental-mutation rules.

## Non-AI / small maintenance changes

A small manual source fix may use the same narrow safe sequence:

1. inspect source/owner/docs;
2. patch the smallest proven cause;
3. update owner README/validator if the contract changed;
4. source audit/diff;
5. external Builder when native/package behavior changes;
6. device abuse where runtime behavior changed.

Do not falsely mark source-only checks as Android build/device proof.

## Push/promotion rules

- do not push local RiftOS changes without explicit project-owner instruction;
- source checks are not Android compilation;
- compiled Android changes require external Builder;
- install/live-abuse the exact built APK before calling runtime behavior device-proven;
- external AI reasoning does not promote trust by itself;
- RiftCLI Bootstrap-0 has no publication/trust authority.

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
- RiftCLI Kotlin code must remain a thin JNI host rather than becoming a second CLI implementation.
