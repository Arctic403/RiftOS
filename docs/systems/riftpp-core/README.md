# Rift++ Core Bootstrap Frontend

## Purpose

`src/riftpp-core.js` is the first executable Rift++ Core frontend. It is independent of the experimental RiftCLI/V0 swarm DSL. It accepts a deliberately bounded subset of `riftpp 1`, produces a source-spanned AST, performs deterministic semantic/type/control-flow checks, and lowers successful programs into the data-only `rift-exec-v1` format consumed by RiftVM.

```text
.riftpp source
 -> Rift++ Core bootstrap frontend
 -> .rxe / rift-exec-v1
 -> RiftVM
 -> RiftRT / RiftOS
```

The frontend runs inside the existing RiftOS JavaScript runtime. The long-term target remains self-hosting in Rift++ after the language/runtime are capable enough.

## Implemented bootstrap slice

The current `0.3.0-bootstrap` slice requires `riftpp 1` and a `module` declaration. It supports:

- functions with explicitly typed parameters/returns;
- primitive `unit`, `bool`, `u32`, `s32`, `string`;
- immutable `let`, mutable `var`, checked assignment and compound assignment;
- lexical block scopes and inner-scope shadowing;
- `if/else`, `while`, `break`, `continue`, short-circuit `and/or`;
- local nominal `struct` declarations, exact struct construction and field reads;
- local nominal tagged-union `enum` declarations with zero/payload cases;
- enum construction through `Type.Case(...)` / `Type.Case`;
- exhaustive `match` on closed enums and `bool`;
- match payload bindings, `_`, whole-value bindings and boolean cases;
- match guards, with guarded cases not counted as exhaustive coverage;
- checked integer arithmetic, strings, comparisons and direct function calls;
- reachable-path return analysis and unreachable-code diagnostics;
- bootstrap prelude `print(value)`.

Struct construction must supply each declared field exactly once. Enum payload arity/types are checked. Struct/enum values are nominal and can pass through locals, function parameters and returns. Composite equality/ordering is intentionally undefined in this bootstrap and fails closed.

Bootstrap pattern limitations remain deliberate: enum payload patterns currently accept bindings or `_`; nested/literal payload patterns are not implemented. Field mutation/place assignment is not implemented yet; rebuild and assign the whole struct instead.

## Still absent

Valid Core syntax not implemented by this slice fails closed. Major missing pieces include imports/module graphs, top-level const, `for`, `loop`, bit operations, field/index assignment, nested match payload patterns, arrays/slices/vec, option/result/`?`, ownership/borrowing, capability/effect lowering, FFI, compute/tensor extensions, the reference interpreter and the self-hosted compiler.

## Public surface

`globalThis.RiftPlusPlusCore` exposes only pure bounded compiler operations:

- `lex(source)`
- `parse(source)`
- `compile(source)`
- `inspect(source)`

It has no filesystem, network, shell, process, Android, MCP or mutation authority. Generated output is independently passed through `prepareRiftExecutable` before compile succeeds.

## Structured-data runtime boundary

RiftVM now provides only five finite composite operations used by Core:

```text
make_struct
get_field
make_enum
enum_is
enum_get
```

These are data-only operations. Composite values cannot implicitly cross the RiftVM host-import boundary. There is no generic object/property opcode, reflection API or authority widening.

## Source ownership

- `src/riftpp-core.js` — lexer/parser/AST, name/type/control-flow checks and `.rxe` lowering.
- `src/riftvm.js` — executable validator/runtime including nominal composite value operations.
- `scripts/test-rift-plus-plus-core-v1.mjs` — source/compiler/runtime proof plus negative language diagnostics.
- `scripts/test-rift-vm.mjs` — independent raw-VM opcode/value/security proof.
- `scripts/test-riftpp-shell.mjs` — normal RiftShell `riftpp` routing and execution-authority boundary.
- `examples/riftpp/core-v1-structured-data.riftpp` — Gate 2 struct/enum/match proof fixture.

## Invariants

- V0 swarm syntax remains separate and non-executable.
- Core source never executes through `eval`, `Function`, shell or host-language code generation.
- Generated output must pass independent RiftVM validation before compile succeeds.
- Runtime authority is not inferred from structured values.
- Unsupported Core syntax/semantics fail closed.
- Same source and compiler version produce deterministic executable structure.

## Validation

`test-rift-plus-plus-core-v1.mjs` executes the base, Control Flow V1 and Structured Data V1 fixtures, then attacks duplicate/missing fields, duplicate enum cases, wrong payload arity/type, non-exhaustive enum/bool matches, guarded exhaustiveness and composite equality. `test-rift-vm.mjs` separately executes raw struct/enum bytecode, malformed composite instructions and the no-composite-host-boundary rule.
