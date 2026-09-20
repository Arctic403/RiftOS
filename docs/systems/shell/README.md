# RiftShell

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

RiftShell is RiftOS's process-owned Android-native command authority.

It is implemented by RiftNativeShell.kt, owned by RiftMcpRuntime, and shared by:
- the native Terminal UI through RiftShellExecutor;
- MCP through rift_shell_exec / RiftToolHost.

There is no trusted-shell WebView fallback and no Android/Linux raw shell escape.

## Source ownership

Primary:
- RiftNativeShell.kt — parser, cwd, RiftFS core commands, workspace status/push routing, app/process helpers.
- RiftNativeShellServices.kt — bounded adapters for Chat Handoff, Dev Lab, Vortex, local agents and RiftLLM.
- RiftHeadlessJsRuntime.kt also hosts the bounded Semnexis V0 bootstrap compiler for the fixed `semx` command family and the read-only generic `qjs` developer command.
- RiftShellExecutor.kt — UI/MCP-neutral asynchronous execution contract.
- RiftMcpRuntime.kt — process singleton owner.
- RiftToolHost.kt — MCP permission gate/audit/result framing.
- RiftHeadlessJsRuntime.kt — bounded QuickJS owner for the riftpp command.
- RiftNativeGit.kt — Git command authority.
- RiftPatchSessions.kt — provenance correlation for direct Shell filesystem mutations that resolve into D:/Workspace.

Retained/unpackaged:
- src/riftshell-batch.js
- old shell paths inside retained src/riftos.js and migration-era JS.

## Process/lifecycle model

RiftMcpRuntime lazily constructs one RiftNativeShell with application context.

RiftNativeShell uses one serialized worker backed by a bounded 8-request queue plus a separate watchdog. Supported commands still execute in order, but queue growth is capped and each invocation has a 60-second terminal deadline.

Activity recreation does not recreate the shell while the Android process remains alive.

close() marks the shell closed and shuts down both the worker and watchdog. Timeout interrupts the active Future; long copy/delete/archive/hash/tree loops cooperatively check `RiftDeadline` so the serialized worker can recover instead of remaining poisoned.

The Terminal UI is only a client; closing Terminal does not close the process shell.

## Command request bounds

Before tokenization:
- maximum command size: 2 MiB UTF-8.

Tokenizer:
- accepts double-quoted tokens, single-quoted tokens or whitespace-separated tokens;
- maximum tokens/arguments: 16384.

This is not a POSIX shell:
- no pipes;
- no redirection;
- no environment expansion;
- no command substitution;
- no raw subprocess syntax.

Unsupported commands fail explicitly.

## Core command families

Native core includes:
- help / pwd / cd / home
- drives / df / sysinfo / native / uptime / version
- ps / kill
- apps / permissions
- ls / tree / stat / cat / head / tail
- write / touch / mkdir / cp / mv / rm
- zip / unzip
- open / browser
- workspace cd/info/ls/status/push
- git
- chat
- devlab
- vortex
- vortex-agent
- riftos-agent
- riftllm-agent
- qjs help/version/eval/run
- semx help/version/self-test/check/dump-graph/dump-plan/dump-ir/emit-arm32-proof/emit-arm32-runtime
- riftpp
- rift-cli

mount / umount are explicitly retired.

The old generic rift / RiftLocalPlatform wrapper is explicitly retired.

### Bounded QuickJS developer command

`qjs` is a headless developer runtime owned by `RiftHeadlessJsRuntime`; it is not Android/Linux shell execution.

Supported surface:
- `qjs help`
- `qjs version`
- `qjs eval <javascript>`
- `qjs run <script.js> [script.js ...]`

The `run` path evaluates up to 64 classic `.js` files in one isolated QuickJS context, in the requested order. It intentionally does not expose arbitrary native module loading or the QuickJS `std`/`os` libraries.

Host globals are limited to:
- `print(...)`
- `console.log/info/warn/error(...)`
- `rift.cwd`
- `rift.readText(path)`

`rift.readText` uses the same canonical RiftFS confinement as the rest of the headless runtime and is read-only. Generic `qjs` has no RiftFS write binding, process/subprocess authority, sockets/network API, Android intent/activity authority, Git authority or recursive RiftShell entry point.

Bounds:
- eval source <=256 KiB UTF-8;
- each loaded text file <=8 MiB;
- aggregate run scripts <=8 MiB;
- <=64 scripts per run;
- captured output <=256 KiB;
- QuickJS evaluation timeout 30 seconds (the outer native shell deadline remains 60 seconds).

There is no live generic batch command.

## RiftFS boundary

All native file paths resolve beneath:

<filesDir>/riftfs

Display paths can use:
- /C:/...
- /D:/...
- RiftFS-relative absolute paths
- cwd-relative paths
- ~ mapped to /D:/Users/Default

Canonical resolution rejects escape outside the RiftFS root.

Volume roots cannot be removed/copied/moved as ordinary entries.

## Read/list bounds

Text output:
- cat/head/tail file <=1 MiB;
- head/tail count clamped to 1..10000.

Recursive tree/list:
- <=5000 rows.

Results report truncation for bounded recursive listing.

## Atomic text mutation

write:
- payload <=1 MiB;
- temp + backup + rename;
- existing target must be a file;
- directory replacement is rejected.

touch:
- creates missing files through the same atomic helper;
- rejects existing non-files.

This audit added the existing-target file-type check so write cannot rename a directory aside and replace it with a file.

When write/touch/mkdir/cp/mv/rm/zip/unzip targets the canonical workspace, the command wrapper opens a bounded `origin=native-shell` patch session before execution, commits it only on success and aborts it on command failure. Non-workspace RiftFS paths are ignored by the workspace provenance manager. This does not change shell authority or command semantics.

## Copy/move

cp/mv:
- require exactly source + destination after optional -f/--force removal;
- reject RiftFS volume roots;
- reject source == destination;
- reject a directory destination inside its own source tree;
- recursively validate each source descendant's canonical path;
- recursively validate each output path;
- <=10000 entries;
- <=256 MiB copied bytes.

### Forced replacement

Previous source deleted the existing destination before the replacement operation succeeded.

This audit changed force replacement to:
1. rename existing destination to a same-parent backup;
2. attempt move/copy;
3. remove backup only after success;
4. on failure, remove partial replacement and restore original destination;
5. explicitly report incomplete rollback if restoration fails.

For move fallback, failure to delete the original source is treated as transaction failure and destination rollback is attempted.

## ZIP creation

zip:
- source must be inside RiftFS and not a volume root;
- output must end in .zip;
- existing output is refused;
- output cannot be inside the source directory;
- source descendants are canonically checked;
- entry paths reject traversal;
- <=10000 entries;
- <=256 MiB aggregate input;
- writes to temp archive then renames into place.

The output-inside-source guard prevents the archive from ingesting its own temporary/output file.

## ZIP extraction

unzip:
- archive must exist as a file;
- destination must not already exist;
- extraction first occurs in a same-parent staging directory;
- rejects absolute, drive-letter, dot/dot-dot and duplicate ZIP paths;
- canonically confines every extracted target beneath the staging directory;
- <=10000 entries;
- <=256 MiB uncompressed bytes;
- publishes by renaming the completed stage;
- failed extraction deletes the stage.

## Logical task/process commands

ps reports:
- protected logical RiftKernel;
- protected Rift Desktop;
- protected Native RiftShell;
- current native Desktop WindowRecords when an Activity is available.

These are RiftOS logical tasks, not Linux process enumeration.

kill:
- cannot target kernel/desktop/shell;
- requires an active Activity;
- closes an existing Desktop WindowRecord through MainActivity/Desktop authority.

No OS process-kill primitive is exposed.

## Installed app listing/opening

apps enumerates native built-ins and existing C:/Programs package candidates.

Installed candidates must now have:
- valid package.json manifest;
- manifest id matching directory name;
- id matching the current package-id regex.

open <app-id>:
- requires exactly one id;
- normalizes known built-in ids to lowercase;
- preserves non-built-in package id case exactly.

This fixes the previous behavior where every installed app id was lowercased before launch.

## Installed-app permission inspection/revocation

permissions / permissions list reports persisted app grants.

permissions revoke <app-id> [capability|all]:
- validates app id and capability token;
- removes one or all persisted grants.

If network/all is revoked while a matching installed app is live:
- RiftShell forwards revocation through active MainActivity;
- RiftBrowserAppHost immediately restores blockNetworkLoads=true.

Other capabilities are checked from persisted grants on the next bridge request.

## Workspace helpers

workspace info/ls/status operate on the native D:/Workspace mapping.

workspace status compares files beneath /workspace/RiftOS-main against .riftgit.json tracked Git blob SHAs.

workspace push delegates to RiftNativeGit.

Workspace helpers do not create a second workspace authority.

## Delegated service families

RiftNativeShellServices is argument parsing/routing only.

It delegates to existing owners:
- chat -> RiftChatHandoff
- devlab -> RiftNativeDevLab
- vortex -> RiftVortexBridgeClient
- vortex-agent -> RiftVortexLocalAgent
- riftos-agent -> RiftOsLocalAgent
- riftllm-agent -> RiftLlmDevClient / RiftTrainDataTaskRunner

The shell does not reimplement those subsystems.

## RiftLLM secret boundary

Pairing tokens are intentionally rejected in shell arguments.

Pairing is performed only in native Settings through RiftSecretStore.

RiftLLM preview/publish fail closed because the retired workspace patch composite has no current bounded native replacement.

## Rift++ execution

riftpp delegates to RiftHeadlessJsRuntime.

The live path is:

RiftShell -> headless QuickJS -> packaged Rift++ Core -> packaged RiftVM.

No Chromium is required.

Normal `run/exec` rejects executable host imports. `run-stateful/exec-stateful` is a separate bounded path that permits only `state.load`, `state.save`, and `state.remove` against an isolated validated checkpoint namespace; it does not expose generic native calls or software verification.

## Native RiftCLI

`rift-cli` delegates to the thin `RiftCliHost` JNI adapter, which calls the C++ RiftCLI core in `libriftcli.so`.

Bootstrap-0 exposes only help/status/architecture plus explicit process-local enable/disable. The native CLI currently has zero mutation, tool, network, model/API, project-memory or planner authority.

Shell availability does not imply RiftCLI is enabled. The process-local switch still defaults OFF after every process restart.

See `docs/systems/riftcli/README.md`.

## MCP authority

RiftToolHost exposes the same process-owned executor through rift_shell_exec.

MCP shell access requires:
- read permission;
- write permission.

This is intentionally stronger than read-only workspace tools because RiftShell can mutate more than workspace-only MCP filesystem state and can call bounded native service families.

ToolHost audit records:
- tool name;
- duration/result;
- only a sanitized first command token for target display.

Shell command arguments are omitted from the audit target to avoid recording secrets/content.

## Retired batch

src/riftshell-batch.js remains retained reference source.

Current Gradle does not package it and RiftNativeShell has no batch command.

Dev Lab/Workspace Code Mode transactions are separate native authorities.

## Source fixes in this audit

- added 2 MiB command-size bound;
- added 16384-token bound;
- forced cp/mv now backs up and restores destination rather than deleting it first;
- recursive copy validates canonical source descendants;
- copy/move rejects destination inside source;
- zip rejects output inside source;
- atomic shell write rejects existing non-file targets;
- shell package listing now enforces folder/id identity and id syntax;
- permission revoke immediately re-blocks live app network access;
- open preserves case-sensitive installed app ids;
- help now lists native mutating/archive/open commands and workspace push;
- restored the shell helper boundary after a corrupted `normalizeDisplay` block had swallowed `resolveFile` and duplicated `tokenize`; path normalization now delegates directly to `RiftVolumePaths.normalizeDisplay`, file resolution uses `RiftVolumePaths.resolveRelative`, and canonical RiftFS confinement remains explicit.

## Critical invariants

- one process-owned executor;
- no ProcessBuilder / Runtime.exec / raw Linux shell;
- no WebView/renderer fallback;
- all file paths stay under RiftFS;
- command/token/text/tree/copy/archive bounds stay explicit;
- forced replacement cannot destroy prior destination before successful replacement;
- recursive operations cannot enter their own destination;
- ZIP extraction remains staged and traversal-safe;
- protected logical tasks cannot be killed;
- MCP shell requires read + write grants;
- credential pairing is never accepted through shell;
- retained batch remains inactive.

## Failure signatures

- shell creates ProcessBuilder/Runtime.exec -> authority collapse;
- browser/Activity destruction kills process-owned shell -> lifecycle regression;
- cp/mv --force destroys destination after failed copy -> transaction regression;
- copying directory into child begins recursive growth -> recursion guard regression;
- zip output inside source is accepted -> self-ingestion regression;
- write replaces a directory -> atomic write type regression;
- shell can escape riftfs -> path confinement failure;
- rift_shell_exec works with read-only MCP permission -> ToolHost gate regression;
- network revoke leaves live app network enabled -> permission revoke regression;
- installed uppercase package cannot open because id is lowercased -> app dispatch regression;
- batch/rift/mount silently falls back to old JS -> migration regression.

## Fix map

Core command/parser/filesystem -> RiftNativeShell.kt.

Delegated service parsing -> RiftNativeShellServices.kt.

Process singleton -> RiftMcpRuntime.kt.

MCP gate/audit/framing -> RiftToolHost.kt.

Terminal UI -> RiftNativeSystemApps.kt.

Rift++ runtime -> RiftHeadlessJsRuntime.kt.

Git -> RiftNativeGit.kt.

Installed app live permission effect -> MainActivity.kt / RiftBrowserAppHost.kt.

## Validation

Second source audit must recheck:
- process ownership and executor serialization;
- no native process escape;
- command/token bounds;
- path normalization/canonical confinement;
- exactly one tokenizer helper, one single-argument confined `resolveFile(displayPath)` helper, and one `joinDisplay` helper remain at class scope;
- text/tree limits;
- atomic write file-type guard;
- cp/mv backup/rollback/source/destination containment;
- ZIP staging/traversal/entry/byte/self-output limits;
- protected ps/kill behavior;
- app id listing/open rules;
- persistent + live network revoke;
- retired batch/mount/rift paths;
- service delegation;
- Rift++ headless route;
- MCP read+write gate and argument-redacted audit.

Builder/device validation remains separate.


## Fixed Rift developer tools

`rift-tool` is a bounded trusted developer-tool router, not a generic JavaScript/process shell.

Current fixed commands:
- `rift-tool gate0-verify` — archival exact-reference/drift verification;
- `rift-tool semantic-compat` — runs the frozen semantic contract against the active compiler/runtime;
- `rift-tool text-model-benchmark` — fixed installed-device UTF-16-code-unit vs UTF-8 representation-cost benchmark used by Rift++ Gate 1B.

The benchmark exposes no arbitrary script, process, network or generic filesystem authority. Its result is measurement evidence, not a hardcoded performance verdict.
