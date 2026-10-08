# Rift MCP System

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-26.**

## Purpose

Rift MCP is RiftOS's process-owned, on-device JSON-RPC tool boundary.

All transports converge on one native server/tool host. Browser compatibility and outbound relay do not receive extra filesystem, shell, Git, or Android authority.

## Source ownership

Core composition:
- `RiftMcpRuntime.kt` — process-owned singleton graph, passive debug hub and current Activity reference.
- `RiftDebugHub.kt` — process-wide bounded passive diagnostics and universal adapter plug.
- `RiftMcpServer.kt` — in-process MCP JSON-RPC framing, relay retry dedupe and persistent operation identity.
- `RiftMcpOperationJournal.kt` — bounded AtomicFile-backed MCP execution/reconciliation journal with monotonic sequences and restart-safe no-replay state.
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

`RiftMcpRuntime` lazily owns singletons for native shell, tool host, MCP server, persistent MCP operation journal, relay, native Git and Vortex bridge. C0.1 removed the Codynex/Rift++ project editor shell commands and their Binder clients; independent editors no longer have a hard-coded RiftShell transport.

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

Current expected count: **21**:
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
- rift_mcp_reconcile
- rift_debug
- rift_local_agent_batch
- rift_workspace_exec

`manifest()` hashes the complete tool-definition JSON with SHA-256 and reports names/count/scope.

`rift_debug` is the passive debugger query surface. It exposes status, events, active spans and components under the existing read grant; it cannot execute, mutate, cancel or widen authority.

`rift_mcp_reconcile` is the persistent recovery surface for ambiguous or interrupted client turns. It returns recent operations or operations newer than a supplied journal sequence plus matching Workspace Records provenance. Execution state is authoritative independently of response delivery. Delivery is recorded only as `queued_to_relay`, `response_not_delivered`, or `unknown`; no UI acknowledgement is inferred. A retained terminal request identity is never re-executed after process restart.

## Permission boundary

ToolHost owns read/write grants in local preferences.

Workspace tools never widen beyond the sandbox merely because the caller is Browser or Relay.

`rift_shell_exec` is the stronger process-owned RiftShell capability and is permission-gated by ToolHost; it is not Android/Linux `/system/bin/sh`.

`rift_local_agent_batch` status/result/list actions require read access; submit/cancel require read and write access. It prevalidates at most 16 steps, binds each retained `requestId` to the exact normalized plan, reserves the process-local RiftOS Local Agent execution authority for the job, runs steps sequentially through `RiftOsLocalAgent`, persists bounded status/results, and never replays unfinished UI actions after restart. Standalone `riftos-agent` work cannot interleave while that lease is held. It does not enter RiftCLI, RiftShell batch, workspace batch, relay transport, or SSE.

### Installed-device batch proof — run #406

Source `e4d32aa87d82840ea0d64e5622b5f647e27b3b55`, Builder run `36271037740` / run #406, publishes a 20-tool MCP manifest containing `rift_local_agent_batch`. Live tests proved: a two-step `status` batch queued and completed with `jobOk=true`; status/result/list persisted and returned the job; exact requestId+plan resubmission deduplicated to the same job; the same requestId with a changed plan failed closed; 17 steps were rejected by the max-16 schema; six retained results paged as 4+2; `failurePolicy=continue` executed a later step after a controlled earlier failure and ended `completed_with_failures`; `failurePolicy=stop` stopped after the first failing step and reported `failed_may_have_applied`; cancelling an already-terminal job returned the unchanged terminal snapshot. Current RiftCLI independently reported `batchV2=false`, `batchV2MaxSteps=0`, `batchOwner=riftos-local-agent`, and `rift-cli batch` failed closed.

Restart/no-replay is **not yet installed-device proven**. The force-stop/reopen attempt did not kill the process while the test job was still nonterminal, so the observed terminal job cannot be used as restart evidence. True in-flight cancellation is also still pending live proof. Source contracts and regression tests remain the authority for those two behaviors until a later installed-device run proves them.

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
- exactly 21 canonical tools in current source until another explicit catalog change;
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
