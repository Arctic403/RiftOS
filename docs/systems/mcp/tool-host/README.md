# RiftToolHost

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

## Purpose

`RiftToolHost` is the canonical device-side MCP capability registry. It owns model-visible tool schemas, aliases, local read/write grants, permission classification, backend routing and bounded tool-call audit metadata.

## Source ownership

- `RiftToolHost.kt`
- workspace backend: `RiftToolSandbox.kt`
- shell backend: process-owned `RiftShellExecutor`
- preferences: `rift-mcp-tools`

## Canonical catalog

`tools()` is authoritative. The current published set is exactly 18 tools:

1. rift_shell_exec
2. rift_info
3. rift_stat
4. rift_hash
5. rift_list
6. rift_read_text
7. rift_write_text
8. rift_mkdir
9. rift_remove
10. rift_move
11. rift_copy
12. rift_archive
13. rift_extract
14. rift_audit
15. rift_scan
16. rift_project_export
17. rift_workspace_diff
18. rift_workspace_exec

`manifest()` hashes the complete definitions JSON with SHA-256 and reports count/names/scope.

The `rift_workspace_exec` public description identifies Project Intelligence **v2**. Patch Session V1 also adds an optional bounded `intent` field to this existing tool schema for provenance evidence. It does not add a tool, grant permission or alter write classification. This intentional schema change changes the manifest hash and may require cached clients to rescan actions.

## Local grants

Preference defaults:
- read: true;
- write: false.

Read-gated tools:
- info/stat/hash/list/read;
- audit/scan;
- project export;
- workspace diff, including bounded checkpoint file-identity evidence;
- workspace exec even when read-only.

`rift_workspace_diff` remains one read-only tool; Patch 2 expands its result evidence with bounded `identity.relations` and similarity-budget metadata without adding mutation authority or another tool.

Write-gated fixed tools:
- writeText;
- mkdir;
- remove;
- move;
- copy;
- archive;
- extract.

`rift_workspace_exec` is always read-gated and additionally write-gated when any normalized operation mutates.

`rift_shell_exec` requires **both read and write** because it has broader native RiftOS authority than the workspace sandbox.

Shell denial messages now identify whether read, write, or both grants are missing.

## Workspace-operation normalization

Canonical Code Mode operation objects are flat:
`{"op":"stat","path":"workspace/..."}`.

ToolHost also accepts the unambiguous legacy/model shorthand:
`{"stat":{"path":"workspace/..."}}`.

Normalization occurs **before** write classification, audit and sandbox execution.

Only known `WORKSPACE_OPS` shorthand is flattened.

This prevents a shorthand mutating operation from bypassing write-permission detection.

## Workspace mutation classification

Workspace operations considered mutating:
- write;
- replace;
- patch;
- patch_range;
- apply_hunks;
- mkdir;
- remove;
- move;
- rename;
- copy;
- archive;
- extract.

Project/search/read/snapshot/reference operations remain read-only.

## Aliases

ToolHost accepts compatibility aliases such as:
- shell -> rift_shell_exec;
- info/stat/hash/list;
- readText/writeText;
- unzip -> rift_extract;
- workspaceExec;
- projectExport;
- workspaceDiff.

Aliases canonicalize before backend mapping and grant classification, so an alias cannot lower permission requirements.

Aliases are not additional entries in `tools()`.

## Backend mapping

`rift_shell_exec` executes through the process-owned shell executor.

Every other canonical tool maps to one fixed sandbox method:
- sandbox.info;
- fs.*;
- workspace.audit/scan/exportProject/diff/exec.

Unknown tools fail before sandbox execution.

## Audit

Audit preference key: `audit`.

Maximum returned/stored audit entries: **100**.

Each record contains:
- timestamp;
- canonical tool;
- bounded/redacted target;
- optional duration;
- ok/error.

Shell audit records only the first command token (sanitized, max 48) plus `[arguments omitted]`; full shell arguments are not written into the ToolHost audit log.

Workspace batch audit records only operation count.

Audit reads are now explicitly clamped to the last 100 entries even if oversized legacy state was migrated.

## Legacy state migration

Once per install state, ToolHost migrates missing read/write/audit preferences from legacy `rift-bridge`.

After migration:
- legacy preference file is cleared;
- old `rift.bridge.pairingKey` secret is removed;
- `legacyStateMigrated` prevents repeated migration.

## Shell lifecycle

ToolHost receives the process-owned shell executor at construction or through `setShellExecutor`.

The unused `clearShellExecutor` API was removed during this audit because no process-lifecycle path detached that singleton.

If shell executor is unexpectedly absent, `rift_shell_exec` fails explicitly rather than falling back to WebView or Android shell.

## Non-ownership boundaries

ToolHost does not own:
- workspace path enforcement/operation implementation -> Sandbox;
- JSON-RPC -> Server;
- relay/browser transport;
- RiftShell command semantics;
- Android UI.

## Source fixes in this audit

- removed obsolete renderer-fallback language from shell tool schema;
- changed Code Mode description from Project Intelligence v1 to v2;
- corrected shell missing-grant errors;
- removed dead `clearShellExecutor`;
- hard-bounded audit reads to 100 entries.

## Critical invariants

- exactly 18 published definitions;
- aliases never appear as additional catalog tools;
- manifest derives from live definitions;
- normalization precedes permission classification;
- shell requires read + write;
- mutating workspace batches require write;
- sandbox remains workspace-only;
- audit never logs full shell arguments;
- audit max remains 100;
- no renderer/native-dispatcher fallback.

## Failure signatures

- manifest count differs from 18 without intentional catalog change -> registry drift;
- alias write succeeds with write disabled -> classification regression;
- shorthand write bypasses write grant -> normalization-order regression;
- shell succeeds with only one grant -> authority regression;
- audit includes shell secrets/arguments -> redaction failure;
- audit grows beyond 100 -> bound regression;
- source schema says PI v1 -> stale client contract.

## Fix map

Schemas/catalog/aliases/grants/audit -> `RiftToolHost.kt`.

Workspace implementation -> Sandbox.

Shell command implementation -> RiftNativeShell.

## Validation

Second source audit must count tool definitions, compare aliases/method map, verify permission functions, normalized workspace mutation detection, audit target redaction/bound, legacy migration and shell executor behavior.
