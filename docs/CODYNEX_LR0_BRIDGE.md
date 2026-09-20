# Codynex LR0 Local Bridge

Status: **IMPLEMENTATION AUTHORIZED**
Date: 2026-09-20

## Goal

Give RiftOS a bounded local control/observation path into the installed Codynex LR0 lab APK without adding a new MCP action name.

ChatGPT continues using the existing `rift_shell_exec` tool.

RiftOS adds one native shell family:

`codynex ...`

## Transport

RiftOS binds explicitly to:

- package: `com.codynex.lr0lab`
- service: `com.codynex.lr0lab.CodynexBridgeService`
- descriptor: `com.codynex.lr0lab.bridge.v1`

Transport is Android Binder.

No raw Android shell, TCP listener, WebView bridge or shared external-storage file is introduced.

## Commands

- `codynex status`
- `codynex read-state <id>`
- `codynex call <function-id>`
- `codynex compile-activate <RiftFS-source-path>`
- `codynex activate`
- `codynex corrupt`
- `codynex recover`
- `codynex clear`
- `codynex cold-restart`

`compile-activate` reads a bounded UTF-8 source file from RiftFS and sends source text to the Codynex lab service.

RiftOS does not encode CXE1 itself.

## Bounds

- explicit package/service binding only;
- bounded JSON request/response;
- bounded Binder transaction timeout;
- source file <= 64 KiB UTF-8 for LR0 lab assembly;
- one operation per shell invocation;
- bridge reconnects once after Binder death;
- no unbounded RPC worker pool.

## Security boundary

Codynex validates the Binder calling UID and only accepts its own package or `com.riftos.app`.

RiftOS does not receive direct access to Codynex app-private files.

All candidate activation and state mutation still occur through Codynex's own loader/validator/transaction/recovery boundaries.

## MCP behavior

No new MCP tool is added.

The existing `rift_shell_exec` command surface is used so the connector manifest remains stable.

Examples:

```text
codynex status
codynex call 0
codynex compile-activate workspace/Codynex/programs/first_lr0.cxeasm
codynex cold-restart
```

## Success gate

The bridge is supported when the installed RiftOS build can:

1. bind to the installed Codynex LR0 app;
2. read runtime stats;
3. invoke hosted functions;
4. compile+activate an external source file through Codynex lab equipment;
5. observe state-preserving live replacement;
6. trigger cold process death and reconnect to recovered state;
7. observe invalid-candidate survival.
