# Rift++ Core Bootstrap Frontend

## Purpose

`src/riftpp-core.js` is the first executable Rift++ Core frontend. It is independent of the experimental RiftCLI/V0 swarm DSL. It accepts a deliberately small, specified subset of `riftpp 1`, produces a source-spanned AST, performs deterministic semantic/type checks, and lowers successful programs into the data-only `rift-exec-v1` format consumed by RiftVM.

This is the bootstrap route that removes the need for C++ or Kotlin to define Rift++ language semantics:

```text
.riftpp source
 -> Rift++ Core bootstrap frontend
 -> .rxe / rift-exec-v1
 -> RiftVM
 -> RiftRT / RiftOS
```

The frontend runs inside the existing RiftOS JavaScript runtime. The long-term target is to rewrite the compiler in Rift++ after the language/runtime are capable enough, then use the bootstrap compiler only for compatibility/recovery.

## Implemented bootstrap slice

The current `0.2.0-bootstrap` slice requires `riftpp 1` and a `module` declaration. It supports function declarations, typed parameters, `unit`, `bool`, `u32`, `s32`, `string`, immutable `let`, mutable `var`, checked assignment (`=`, `+=`, `-=`, `*=`, `/=`, `%=`), lexical block scopes, `if/else` and `else if`, `while`, `break`, `continue`, short-circuit `and/or`, decimal/hex integer literals, strings, booleans, direct function calls, arithmetic, comparisons, unary `not`/`+`/`-`, `return`, expression statements and the bootstrap prelude intrinsic `print(value)`.

`main` must be `fn main()` with unit return. User function arguments evaluate left-to-right. Integer operations inherit RiftVM checked arithmetic. `let` remains immutable, `var` is mutable, names resolve from the innermost lexical scope outward, same-scope duplicates fail, and inner blocks may shadow outer bindings. Non-`unit` functions must return on every reachable path; unreachable statements after unconditional control transfer are rejected. `print` is a reserved bootstrap-prelude name and cannot be shadowed by functions, parameters or locals.

Valid Core syntax not implemented by this slice fails closed with a structured `RiftCoreCompileError`; it is not silently reinterpreted. Notably absent today: imports, top-level const, structs/enums, `match`, `for`, `loop`, bit operations, collections, result/option, ownership/borrowing, capability/effect clauses, FFI, tensors, tasks and compute extensions.

## Public surface

`globalThis.RiftPlusPlusCore` exposes pure bounded compiler operations only:

- `lex(source)`
- `parse(source)`
- `compile(source)`
- `inspect(source)`

It has no filesystem, network, shell, process, Android, MCP or mutation authority. `compile` returns `riftpp-core-compile-result/1` with the AST plus deterministic `rift-exec-v1` object/text.

## Diagnostics

Compiler failures carry a structured diagnostic with `code`, `message`, source `span`, violated `rule`, and optional `help`. Host bounds cap source bytes, token count, functions, parameters and locals.

## Source ownership

- `src/riftpp-core.js` — lexer, parser, AST, semantic/type checking, `.rxe` lowering and bounded global compiler surface.
- `src/riftvm.js` — executable validator/runtime; the compiler never bypasses it and validates generated output through `prepareRiftExecutable`.
- `scripts/test-rift-plus-plus-core-v1.mjs` — source -> compiler -> `.rxe` -> RiftVM executable proof and negative diagnostics.
- `scripts/test-riftpp-shell.mjs` — normal RiftShell `riftpp` command routing and execution-authority boundary.
- `src/riftos.js::runRiftppShell` — shell-only adapter for self-test/check/compile/inspect/run/exec; it does not belong to or enable experimental RiftCLI.
- `examples/riftpp/core-v1-hello.riftpp` — first human-written executable Core source fixture.

## Invariants

- V0 swarm syntax remains separate and non-executable.
- Core source never executes through `eval`, `Function`, shell or host-language code generation.
- Generated output must pass independent RiftVM validation before compile succeeds.
- Runtime authority is not inferred from source; future capability lowering must remain explicit and revalidated by RiftRT/RiftVM.
- Unsupported Core syntax fails closed rather than gaining accidental semantics.
- Same source and compiler version produce deterministic executable structure.

## Validation

Run `scripts/test-rift-plus-plus-core-v1.mjs` and `scripts/test-riftpp-shell.mjs` in the normal Node validation environment. The Core test executes both `core-v1-hello.riftpp` and `core-v1-control-flow.riftpp`, verifies mutable assignment, scoped shadowing, branches, loops, break/continue and short-circuit `and/or`, then checks immutable-assignment, illegal loop control, incomplete return paths, unreachable code, unsupported features and absence of dynamic-code/process escape paths.
