# RiftOS native Shell — live Generic Tool link trust (Blockbench Mobile)

**Source-only change.** NativeShell now parses:

```text
tool trust link blockbench-mobile describe,status,selftest,diagnostics,submit,result,export_info,export_chunk
tool trust status blockbench-mobile
tool trust stop blockbench-mobile
```

The `link` verb only packages a Core request
`riftos.core.app-connection-request/1` with operation
`tool-session-link-start`, the saved tool ID, and 1–8 unique named
operations. It neither grants authority nor sends Binder transactions itself.

The *selected independent Core* v0.6.23 (source candidate at
`/D:/Workspace/RiftComponents/`) validates the signer-pinned saved grant,
provider live session ID, user consent, and each subsequent call. Version
v0.6.21 currently selected does not recognize the operation; invoking
`tool trust link` before manual Core activation will fail closed.

**No active host APK was rebuilt or installed** as part of this source change.
User needs to manually build/install the updated host from the normal
`Arctic403/RiftOS` / `Riftos-builder` workflow and manually select
the v0.6.23 Core DEX using Recovery. No automatic staging.

With the updated Blockbench Mobile v0.2.3 APK, Core grants one temporary,
memory-only permission set tied to a single WebMessagePort connection identity.
On disconnect/reconnect the previous grant is unusable; the user approves
a new link session once. Existing `tool trust start ... minutes` time-limited
mode remains intact and `tool trust stop` revokes both kinds of trust.

Security: signer pinned, exact saved grant scope, Shell-only approval,
RAPP tool calls remain individually consented, no arbitrary JS tool.

## 2026-11-12 update — tool trust app <approved-id>

The user observed that Android sometimes recreates Blockbench Mobile's
WebView while switching back to RiftOS, causing a new editor `sessionId`
and revoking the original connection-bound grant. The new native Shell
command `tool trust app blockbench-mobile` requests a Core-managed
`tool-session-app-start` approval. The command itself never grants
permissions.

Independent Core v0.6.24 (separate manual-build candidate) reads all
previously approved operations from the saved signer-pinned registration,
displays one explicit scope consent, and validates the provider's
foreground-service `appRunId` on every subsequent trusted Shell call.
The grant survives editor WebView recreation but is invalid after app-linked
service stop, task removal, process death, force-stop, or Core restart.
RAPP callers retain per-invocation consent.

This is NOT Android device administrator/root access; it is an application-
specific grant for exactly the saved Blockbench Binder tool operations.
The external graphical Shell and older selected Core are unchanged.
