# RiftToolHost

## Purpose

`RiftToolHost` is the canonical device-side capability registry for Rift MCP. It is the single place that defines model-visible tool names/schemas, aliases, method mapping, read/write classification, local grants and bounded audit metadata.

## Source ownership

`android/app/src/main/java/com/riftos/app/RiftToolHost.kt`.

## Registry contract

`tools()` constructs the live MCP tool array. `manifest()` derives the canonical name list, count and SHA-256 directly from that array. If a tool is executable but absent from `tools()`, clients cannot discover it; if a tool is advertised but lacks a mapping/backend, calls fail. Registration and dispatch must therefore change together.

## Call flow

```text
tool name + args
  -> canonicalName()
  -> methodFor() / shell special case
  -> normalize arguments
  -> classify mutation (`requiresWrite`)
  -> check local grants
  -> capture AI rollback state when needed
  -> audit/journal start
  -> sandbox.handleAsync() OR RiftShellBridge.execute()
  -> audit/journal finish
  -> host result
```

`rift_workspace_exec` accepts canonical flat operations and defensively normalizes an unambiguous legacy shorthand before permission classification and journaling, preventing shorthand writes from bypassing write gating.

## Permission model

Preferences live in `rift-mcp-tools`. `allowRead` defaults true; `allowWrite` defaults false. Mutating single-operation filesystem tools require write. Code Mode requires read and write only when its operation list mutates. Shell exec requires read and write.

## Audit

A bounded recent audit list records tool, target, success/failure and error metadata without turning the log into a copy of file contents. The MCP settings Activity can clear it.

## Critical invariants

- `tools()`, alias mapping, method mapping and permission classification must stay synchronized.
- Normalize before permission checks.
- Never let a client-supplied alias change read/write classification.
- The host is authoritative even if a browser/relay client claims a different schema.
- Do not move workspace containment checks out of the sandbox.

## Failure signatures

- Tool absent from client after a real action rescan -> check `tools()` first.
- Tool listed but unsupported at call time -> `canonicalName`/`methodFor` mismatch.
- Write unexpectedly allowed/denied -> `requiresWrite`, `workspaceBatchMutates`, preference state.
- Audit target wrong -> `auditTarget` mapping.
- Shell tool says bridge unavailable -> runtime registration/`RiftShellBridge`, not sandbox.

## Change checklist

When adding a tool: add schema to `tools()`, canonical alias if needed, backend method mapping, read/write classification, audit target, journal behavior if mutating, tests/docs, then verify manifest count/hash through `rift_info`.

## Validation

Transport validation checks important mappings. For any catalog change, inspect `tools()` count, call the tool through MCP, confirm permission denial/success cases and ensure `rift_info.mcpManifest.names` includes it.
