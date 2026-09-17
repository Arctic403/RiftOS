# RiftRT `rift-vm` Engine

## Purpose

`rift-vm` is the first executable target for Rift++. It runs a validated, data-only Rift executable payload inside the existing RiftRT process/window/capability lifecycle. It does **not** evaluate JavaScript supplied by the package, execute ELF files, invoke a shell, or widen MCP.

Distribution and execution are separate:

```text
program.rift                 portable installer package
  riftrt.json                engine = rift-vm
  main.rxe                   Rift executable payload

.rift -> RiftApps -> C:/Programs/<id> -> RiftRT -> RiftVM -> main.rxe
```

## Executable contract

The first payload schema is `rift-exec-v1` with ABI `riftvm-1`.

```json
{
  "format": "rift-exec-v1",
  "abi": "riftvm-1",
  "entry": "main",
  "imports": ["app.setTitle"],
  "constants": [
    {"type":"string","value":"hello"}
  ],
  "functions": {
    "main": {
      "params": 0,
      "locals": 0,
      "code": [
        {"op":"const","index":0},
        {"op":"print"},
        {"op":"halt"}
      ]
    }
  },
  "limits": {"maxSteps":10000,"maxStack":256,"maxCallDepth":16}
}
```

V1 scalar constants are `unit`, `bool`, `u32`, `s32`, `f64`, and `string`. Runtime values may additionally contain nominal immutable `struct`, tagged `enum`, and bounded immutable `vec` composites constructed only by validated VM instructions. Vector capacity is encoded in `make_vec` and is hard-capped at 64 items. Integer arithmetic is checked; overflow traps. Proven Gate 6A semantics enforce finite `f64` values/results, canonicalize negative zero to positive zero, and reject divide/modulo by zero on the installed Core `0.7.0-bootstrap` runtime. Branch conditions require `bool`. Host calls must be declared in the executable import table before execution, and composite values cannot implicitly cross that host boundary.

## VM instructions

V1 supports bounded constants/locals/stack operations, checked arithmetic/comparison, string concatenation, nominal structured operations (`make_struct`, `get_field`, `make_enum`, `enum_is`, `enum_get`), bounded collection operations (`make_vec`, `vec_len`, `vec_get`, `vec_push`, `vec_set`), proven Gate 5 state operations (`state_save`, `state_load`, `state_remove`), and proven Gate 6A data-only `value_sha256`, plus jumps, calls/returns, declared host imports, output, and halt. `vec_get` returns `Option.Some/None`; `vec_push` and `vec_set` return `Result.Ok/Err` replacement values instead of mutating the original vector. Unsupported opcodes fail validation before execution.

The VM has hard ceilings for function count, constants, instructions, locals, parameters, stack depth, call depth, string size and executed steps. Serialized executable input is capped at 8 MiB before JSON parsing, and aggregate normalized constant-string payload is capped at 4 MiB. Composite values are additionally capped at depth 32; rendered `print` values are capped at 64 KiB; public result conversion is capped at 4096 visited values and 64 KiB of aggregate string payload. Gate 5 state payloads are capped at 64 KiB and canonical type descriptors at 4 KiB. A program can request smaller execution limits but cannot raise the runtime hard ceilings.

## Host boundary

`src/riftrt.js` owns the host adapter. RiftVM itself receives only an `invoke(method,args)` callback and cannot discover RiftOS globals. The adapter exposes a finite dotted method map; share is exposed to RiftVM as `share.text` and still routes through the existing `share` capability check. Gate 5 `state.load/state.save/state.remove` map to the existing app-level `storage` declaration and an app-private `riftvm-state.json`; the state store is stat-checked as a bounded regular file before read, strictly parsed, and capped at 16 validated non-poison keys, 64 KiB per payload, and 512 KiB total. Capability-bearing imports are checked against both `riftrt.json` declarations and installed manifest permissions. `storage` is not added to the global RiftKernel capability registry, and state operations do not expose generic RiftFS paths.

There is no `eval`, `new Function`, generic JS import, raw native dispatcher, process creation or shell opcode.

The VM validates executable structure and runtime operation safety, not the full Rift++ source type system. Compiler-produced `.rxe` carries the compiler's static generic/element type guarantees; hand-authored `.rxe` may still be dynamically ill-typed and trap at runtime. For Gate 5 state instructions, however, the VM independently parses a canonical compiler-generated type descriptor and validates the decoded checkpoint value against that descriptor before returning it. Corrupt JSON, schema mismatch, value-shape mismatch, noncanonical descriptors and oversize state fail closed. Ordinary `host` calls still reject composites; state serialization is a dedicated bounded path, not a generic host bridge. This does not widen capability or host authority.

## Bootstrap boundary

`rift-exec-v1` is a bootstrap executable ABI, not a claim that Rift++ Core V1 is already complete. Installed `0.7.0-bootstrap` lowers its proven Gates 0–6A subset into this format. Gate 5 state/effect support has cross-launch persistence evidence, and Gate 6A finite numeric/parameter identity has installed two-launch proof on the same ABI with zero parameter updates. If later Core semantics need a stronger typed/optimized ABI, that evolution happens as a named compatible executable version rather than silently changing `rift-exec-v1`.

## Source ownership

- `src/riftvm.js` — executable validation and VM semantics.
- `src/riftrt.js` — installed-app engine selection, UI/session lifecycle and host adapter.
- `src/riftapps.js` — unchanged `.rift` installation/registry boundary.
- `examples/riftpp/hello-rift-executable.rift` — importable first executable fixture.
- `scripts/test-rift-vm.mjs` — executable/limits/security regression test, including independent raw vector/Option/Result semantics.

## Invariants

- `.rift` remains the installer; `.rxe` is the installed executable payload.
- Guest instructions are data, never evaluated as JavaScript.
- Unsupported/malformed executables fail before execution.
- Program authority cannot exceed package + `riftrt.json` declarations and persisted user grants.
- Runtime limits are fail-closed, including vector capacity/index semantics, composite depth, rendered output size and public-result expansion.
- RiftVM adds no MCP tool family and no shell/process authority.

## Validation

Run `scripts/test-rift-vm.mjs` and the normal root source checks. Import `examples/riftpp/hello-rift-executable.rift`, launch it from Programs/RiftRT, and verify the native RiftDesktop window reports the `RIFT VM` engine, prints `Rift++ executable online` and `42`, and closes through the normal RiftRT process lifecycle.
