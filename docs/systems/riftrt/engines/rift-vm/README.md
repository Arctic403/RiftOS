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

V1 scalar constants are `unit`, `bool`, `u32`, `s32`, `f64`, and `string`. Runtime values may additionally contain nominal immutable `struct`, tagged `enum`, and bounded immutable `vec` composites constructed only by validated VM instructions. Vector capacity is encoded in `make_vec` and is hard-capped at 64 items. Integer arithmetic is checked; overflow traps. Division/modulo by zero trap. Branch conditions require `bool`. Host calls must be declared in the executable import table before execution, and composite values cannot implicitly cross that host boundary.

## VM instructions

V1 supports bounded constants/locals/stack operations, checked arithmetic/comparison, string concatenation, nominal structured operations (`make_struct`, `get_field`, `make_enum`, `enum_is`, `enum_get`), bounded collection operations (`make_vec`, `vec_len`, `vec_get`, `vec_push`, `vec_set`), jumps, calls/returns, declared host imports, output, and halt. `vec_get` returns `Option.Some/None`; `vec_push` and `vec_set` return `Result.Ok/Err` replacement values instead of mutating the original vector. Unsupported opcodes fail validation before execution.

The VM has hard ceilings for function count, constants, instructions, locals, parameters, stack depth, call depth, string size and executed steps. Composite values are additionally capped at depth 32; rendered `print` values are capped at 64 KiB; public result conversion is capped at 4096 visited values and 64 KiB of aggregate string payload. A program can request smaller execution limits but cannot raise the runtime hard ceilings.

## Host boundary

`src/riftrt.js` owns the host adapter. RiftVM itself receives only an `invoke(method,args)` callback and cannot discover RiftOS globals. The adapter exposes a finite method map. Capability-bearing imports are checked against both `riftrt.json` declarations and installed manifest permissions, then routed through the existing RiftRT `hostCall` permission path.

There is no `eval`, `new Function`, generic JS import, raw native dispatcher, process creation or shell opcode.

The VM validates executable structure and runtime operation safety, not the full Rift++ source type system. Compiler-produced `.rxe` carries the compiler's static generic/element type guarantees; hand-authored `.rxe` may still be dynamically ill-typed and trap at runtime. This does not widen capability or host authority.

## Bootstrap boundary

`rift-exec-v1` is a bootstrap executable ABI, not a claim that Rift++ Core V1 is already complete. The next Rift++ compiler can lower its first stable Core slice into this executable format. If later Core semantics need a stronger typed/optimized ABI, that evolution happens as a named compatible executable version rather than silently changing `rift-exec-v1`.

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
