# Rift MCP System

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

## Purpose

Rift MCP is RiftOS's process-owned, on-device JSON-RPC tool boundary.

All transports converge on one native server/tool host. Browser compatibility and outbound relay do not receive extra filesystem, shell, Git, or Android authority.

## Source ownership

Core composition:
- `RiftMcpRuntime.kt` — process-owned singleton graph, passive debug hub and current Activity reference.
- `RiftDebugHub.kt` — process-wide bounded passive diagnostics and universal adapter plug.
- `RiftMcpServer.kt` — in-process MCP JSON-RPC framing and relay retry dedupe.
- `RiftToolHost.kt` — canonical tool schemas, read/write grants and tool audit.
- `RiftToolSandbox.kt` — workspace filesystem + Project Intelligence/Code Mode.
- `RiftSourceIntelligenceV2.kt` — shared lexical source/dependency analyzer for normal PI-v2 and candidate semantic deltas.
- `RiftPatchSessions.kt` — local mutation-provenance claims for MCP writes; it is evidence-only and not a permission owner.
- `RiftMcpActivity.kt` — local permission/relay/status UI.

Related narrow owners:
- `RiftProjectExporter.kt`;
- `RiftMcpRelayClient.kt` / `RiftRelaySettings.kt`;
- process-owned `RiftNativeShell.kt`;
- exact-origin `RiftBrowserMcpAppBridge.kt`.

## Process ownership

`RiftMcpRuntime` lazily owns singletons for native shell, tool host, MCP server, relay, native Git, Vortex bridge and the Codynex LR0 Binder bridge.

MainActivity registration is a weak UI reference only.

Same-process Activity recreation does not explicitly destroy MCP/shell singletons. Android process death destroys them and a new process reconstructs them lazily.

## Canonical flow

```text
browser compatibility OR relay/local caller
 -> RiftMcpServer
 -> RiftToolHost
 -> RiftToolSandbox / RiftNativeShell / bounded owner
 -> structured MCP result
```

There is no shell WebView executor and no general native dispatcher.

## Tool catalog

The authoritative catalog is `RiftToolHost.tools()`.

Current expected count: **19**:
- rift_shell_exec
- rift_info
- rift_stat
- rift_hash
- rift_list
- rift_read_text
- rift_write_text
- rift_mkdir
- rift_remove
- rift_move
- rift_copy
- rift_archive
- rift_extract
- rift_audit
- rift_scan
- rift_project_export
- rift_workspace_diff
- rift_debug
- rift_workspace_exec

`manifest()` hashes the complete tool-definition JSON with SHA-256 and reports names/count/scope.

`rift_debug` is the single passive debugger query surface. It exposes status, events, active spans and components under the existing read grant; it cannot execute, mutate, cancel or widen authority.

## Permission boundary

ToolHost owns read/write grants in local preferences.

Workspace tools never widen beyond the sandbox merely because the caller is Browser or Relay.

`rift_shell_exec` is the stronger process-owned RiftShell capability and is permission-gated by ToolHost; it is not Android/Linux `/system/bin/sh`.

`rift_workspace_exec` requires write permission only when the requested batch mutates. Its optional `intent` field is bounded provenance metadata only; ToolHost permission classification is still derived from the normalized operations.

`rift_workspace_diff` remains read-only and returns bounded checkpoint-relative structural identity evidence in addition to raw file/event diffs. Exact SHA relations are labeled exact; heuristic similarity relations are labeled non-exact and expose whether the bounded comparison budget prevented exhaustive correlation. Patch 4 also gives the query source a deterministic candidate summary, record-chain integrity state and separate operational/trusted-checkpoint state; the full private freeze API is intentionally not mapped through MCP.

Patch 5 adds a separate internal ToolHost→sandbox candidate-impact seam for the future Local Agent. It derives scope from Patch Manifest V1 and is not present in the MCP catalog or backend method map.

Exact details are verified in the Tool Host/Sandbox audits.

## MCP settings Activity

The exported `riftos://mcp` Activity is a local configuration/status UI, not a command endpoint.

It:
- reads/applies ToolHost read/write grants;
- configures optional outbound relay;
- never displays saved pairing-token plaintext;
- shows tool manifest count/hash;
- shows bounded recent ToolHost audit;
- refreshes relay/status once per second while resumed and removes callbacks on pause.

## Transport equality

Browser compatibility and relay both call the same `RiftMcpServer`.

The browser bridge uses exact-origin WebMessage JSON-RPC.

The relay is outbound/device-initiated and supplies a stable retry key used only for relay `tools/call` idempotency.

Neither transport changes ToolHost grants or workspace scope.

## Source cleanup in this audit

The `rift_shell_exec` tool description was corrected to remove the stale claim that a trusted compatibility fallback still exists.

## Non-ownership boundaries

MCP overview does not own:
- tool implementation details -> ToolHost/Sandbox/native owners;
- relay socket/reconnect policy -> Relay;
- browser page protocol -> Browser MCP compatibility;
- Git -> RiftNativeGit;
- filesystem aliases -> RiftFS.

## Critical invariants

- one process-owned ToolHost/Server graph;
- exactly 18 canonical tools until an explicit catalog change;
- browser/relay share the same grants and server;
- workspace tools remain workspace-scoped;
- no WebView shell fallback;
- no raw Android shell;
- MCP settings Activity is configuration/status only;
- process ownership is not confused with process-death persistence.

## Failure signatures

- transport sees a different tool catalog -> shared-server/host regression;
- Activity recreation creates competing ToolHosts -> process ownership regression;
- browser/relay bypasses ToolHost permissions -> authority regression;
- schema mentions removed WebView fallback -> documentation/schema drift;
- workspace tool escapes workspace -> Sandbox regression.

## Fix map

Composition/lifetime -> `RiftMcpRuntime.kt`.

JSON-RPC/retry/correlation -> `RiftMcpServer.kt`.

Tools/grants/audit -> `RiftToolHost.kt`.

Workspace execution -> `RiftToolSandbox.kt`.

Configuration UI -> `RiftMcpActivity.kt`.

Relay/browser transports -> their dedicated subsystems.

## Validation

Source verification must recheck runtime singleton ownership, canonical flow, exact 18-name catalog, shared transport server, permission owner, settings-Activity lifecycle and absence of renderer fallback.

Child subsystem details require their own audits. Builder/device proof remains separate.
