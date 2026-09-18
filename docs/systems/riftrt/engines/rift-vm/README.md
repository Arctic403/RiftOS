# RiftVM Engine

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

src/riftvm.js is the live packaged virtual machine used by RiftOS's native headless Rift++ shell path.

Current production activation:

RiftNativeShell -> RiftHeadlessJsRuntime -> QuickJS -> packaged riftvm.js

The old RiftRT VM session manager in src/riftrt.js is retained/inactive.

## Source ownership

Live:
- src/riftvm.js
- RiftHeadlessJsRuntime.kt
- RiftNativeShell.kt

Tests/validators:
- scripts/test-rift-vm.mjs
- scripts/test-rift-plus-plus-core-v1.mjs
- scripts/validate-rift-wiring.mjs

## Format and ABI

Executable format: rift-exec-v1.

ABI: riftvm-1.

Preparation validates the complete executable before execution.

## Opcodes

The finite opcode set covers constants/locals/stack, arithmetic/comparison/boolean operations, strings, structs/enums, bounded Vec, state save/load/remove, value SHA-256, jumps/calls, explicit host calls, print, return and halt.

There is no eval, new Function or native process opcode.

## Hard preparation limits

- functions: 256
- imports: 64
- constants: 4096
- total instructions: 100000
- instructions/function: 65536
- parameters/function: 64
- locals/function: 512
- composite fields/items: 64
- Vec capacity: 256
- composite depth: 32
- public result values: 4096
- public/display string bytes: 65536
- executable JSON: 8 MiB
- aggregate constant strings: 4 MiB
- VM max steps hard ceiling: 1000000
- stack hard ceiling: 4096
- call-depth hard ceiling: 64
- runtime string bytes: 65536
- state payload: 65536 bytes
- state schema: 4096 bytes.

Executable-declared limits are validated inside those ceilings.

## Production shell execution limits

The current headless shell further tightens execution to:
- max steps: 100000
- max stack: 1024
- max call depth: 32
- yield interval: 512 instructions
- printed lines: <=256
- aggregate printed bytes: <=65536
- QuickJS evaluation timeout: 120 seconds.

The VM also checks shouldCancel when a host supplies it and yields at a bounded interval.

## Data types

VM supports unit, bool, u32, s32, f64, string, struct, enum and bounded Vec.

u32/s32 are internally BigInt-bounded. f64 must be finite JSON numbers and negative zero is canonicalized to zero. Arithmetic checks division/modulo by zero and integer overflow.

## Composite boundaries

Composite values have maximum nesting depth 32. Vec capacity never exceeds 256. Out-of-range Vec get returns Option.None. Vec push/set return Result.Err rather than silently exceeding capacity or index bounds.

Names reject poison keys such as __proto__, prototype and constructor.

## Host imports

Executable imports must match the finite dotted host-method grammar and be declared before a host opcode can reference them.

Composite values cannot cross the generic host import boundary implicitly.

The VM itself can execute host imports only when the embedding owner supplies host.invoke.

### Current production shell boundary

RiftHeadlessJsRuntime inspects every executable before execution. Normal `riftpp run/exec` still rejects it when `imports.length > 0`.

The explicit `riftpp run-stateful/exec-stateful` commands supply `host.invoke` only for `state.load`, `state.save`, and `state.remove`. State is isolated by a caller-provided validated namespace, keys are SHA-256 mapped inside `riftfs/system/riftpp-state`, records are atomically replaced, each serialized record is capped at the VM's 65536-byte state limit, and each namespace is capped at 256 records.

No other VM host import becomes available through this path. This remains intentionally narrower than the VM engine's abstract import capability.

## State opcodes

The VM implements state_save, state_load and state_remove.

Preparation requires the corresponding declared state.save, state.load and state.remove imports.

State schemas are canonical descriptor JSON, bounded in depth, validate exact value shape and are embedded into the state envelope.

State payload format: riftvm-state-v1.

Payload max: 65536 bytes.

Corrupt JSON, schema mismatch, shape mismatch, invalid finite numeric values and oversized state are rejected.

### Current activation status

These state opcodes are engine-supported, covered by `test-rift-vm.mjs`, and now wired through the explicit native `run-stateful` / `exec-stateful` mode. Normal `run/exec` remains import-free.

Source wiring alone is not device persistence proof. Promotion still requires Builder compilation/package/signing plus installed save/reload evidence across a complete process restart.

## value_sha256

value_sha256 canonicalizes bounded VM public data and hashes at most 65536 UTF-8 bytes through SHA-256.

Struct keys are sorted for stable hashing.

The headless QuickJS runtime supplies only a SHA-256 WebCrypto polyfill backed by Java MessageDigest.

## Headless host capabilities

QuickJS exposes only request/result exchange, UTF-8 encoding, SHA-256, bounded RiftFS readText and bounded atomic RiftFS writeText.

It exposes no DOM/window, WebView, network, Android intent, arbitrary native call, process execution or ambient shell globals.

Text read/write max: 8 MiB.

Path access is canonicalized under app-private RiftFS.

## Atomic compiled output

Headless .rxe output uses same-directory temp/backup/rename replacement.

During this audit output publication was hardened so an existing **directory** at the requested .rxe path is rejected rather than renamed/replaced by a file.

## Tests

test-rift-vm.mjs exercises executable validation, import declaration, bounded struct/enum/Vec, strings, deterministic value hashing, f64 rules, state schema/shape validation, poison/unsupported opcode rejection, composite host-boundary rejection, depth/display/public-result limits, integer overflow and step limits.

During this audit the stale assertion that a .rift package currently runs main.rxe through the old RiftRT engine was removed. Installed package runtime activation belongs to the Apps subsystem.

## Non-ownership boundaries

RiftVM does not own Rift++ source parsing/type checking, installed-program rendering, a persistent state backend, generic native capabilities or package installation.

## Critical invariants

- format/ABI validation occurs before execution;
- max Vec remains 256;
- no eval/native process execution;
- hard step/stack/call/output/data limits remain enforced;
- host calls require declared imports plus explicit host.invoke;
- production shell remains import-free unless an audited host is deliberately added;
- state opcode existence is not documented as live persistence;
- headless output cannot replace a directory.

## Failure signatures

- shell executes imported host capability -> production-host boundary regression;
- Vec capacity >256 -> VM resource regression;
- composite passes directly into generic host import -> boundary regression;
- corrupt state shape loads -> state-validation regression;
- unbounded output/result -> resource regression;
- .rxe compile replaces an existing directory -> atomic-output regression;
- docs claim installed .rift RiftRT VM engine is live -> stale activation claim.

## Fix map

Opcode/ABI/value semantics -> src/riftvm.js.

Current QuickJS/path/output/host activation -> RiftHeadlessJsRuntime.kt.

Shell command routing -> RiftNativeShell.kt.

Source compiler -> Rift++ Core subsystem.

Future state host -> new explicitly audited headless capability owner.

## Validation

Second source audit must verify Gradle packaging, exact format/ABI, finite opcode set, hard limits, preparation validation, step/stack/call/Vec/composite/output bounds, state schema/payload rules, host import declaration/boundary, production shell import rejection, headless capability set/path containment/atomic output, focused test coverage and removal of stale package-runtime claims.

Node/Builder/device tests remain separate from source verification.
