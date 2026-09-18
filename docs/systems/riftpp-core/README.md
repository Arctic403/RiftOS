# Rift++ Core Bootstrap Frontend

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

src/riftpp-core.js is the current packaged Rift++ bootstrap compiler frontend.

It accepts bounded riftpp/1 source, produces a source-spanned AST, performs semantic/type/control-flow/effect checks, links bounded source modules, and lowers successful programs to rift-exec-v1 / riftvm-1 executables.

Current compiler version:
0.7.2-bootstrap.

## Live activation

Gradle packages:
- src/riftpp-core.js
- src/riftvm.js

RiftHeadlessJsRuntime loads both into QuickJS for the native RiftShell riftpp command.

Source compilation is live.

Effectful executable imports are a separate activation question: normal production `riftpp run/exec` still rejects any executable whose imports list is non-empty. The explicit `run-stateful` / `exec-stateful` path permits only `state.load`, `state.save`, and `state.remove` through a bounded namespace-scoped native checkpoint host; every other import remains denied.

## Source ownership

Primary:
- src/riftpp-core.js

Runtime validator/executor:
- src/riftvm.js

Live Android host:
- RiftHeadlessJsRuntime.kt
- RiftNativeShell.kt

Focused tests:
- scripts/test-rift-plus-plus-core-v1.mjs
- scripts/test-rift-vm.mjs
- scripts/test-riftpp-shell.mjs
- scripts/validate-rift-wiring.mjs

## Public API

globalThis.RiftPlusPlusCore exposes:
- version
- language
- targetFormat
- targetAbi
- lex
- parse
- compile
- compileProgram
- inspect
- inspectProgram

The compiler module itself has no filesystem, network, Android, MCP or process authority.

## Core source limits

- source: 256 KiB UTF-8 per module
- tokens: 50000
- functions: 256
- parameters/function: 64
- locals/function: 512
- struct fields / enum payload/cases: 64
- named local types: 256
- Vec capacity: 1..256
- generic/type nesting: 32
- parser recursion families: 128
- use declarations/module: 64
- linked modules: 64 total
- aggregate linked source: 1 MiB
- linked symbol name: 96 UTF-8 bytes
- declared effects/function: 16
- generated checkpoint schema: 4096 UTF-8 bytes

Module linker expression/pattern/statement/block rewrites and codegen expression/block traversal have independent 128-depth guards.

## Current language surface

Implemented bootstrap constructs include:
- module declaration and bounded use module.path [as alias]
- typed functions and unit main entrypoint
- unit, bool, u32, s32, finite f64 and string
- immutable let and mutable var
- checked assignment and compound assignment
- lexical block scope and shadowing
- if/else and while
- break and continue
- return completeness and unreachable-code rejection
- and/or/not with short-circuit lowering
- local nominal struct and enum declarations
- exact struct construction and field reads
- enum construction
- exhaustive match over bool, enums, Option and Result
- match guards
- Vec<T,N>, Option<T>, Result<T,E>
- bounded Vec literals and len/get/push/set
- checked numeric arithmetic
- string concatenation
- string_len, string_find, string_slice, string_replace
- value_sha256
- checkpoint_save/load/remove compiler built-ins
- repair evaluation compiler built-ins
- software evaluation compiler built-ins
- print

for and loop are recognized Core syntax but deliberately fail as not implemented.

Top-level const is reserved grammar/future syntax, not implemented by this bootstrap.

Field/index place mutation is not implemented.

## Module linking

compile() rejects source containing use declarations and requires compileProgram() for linked programs.

compileProgram():
- rejects root duplicated in dependency map
- requires every imported module to be supplied
- requires supplied module identity to match its declared module name
- rejects duplicate imports
- rejects alias collisions
- rejects cycles
- rejects unused supplied dependency modules
- rewrites linked symbols deterministically
- closes the source module graph into one executable

Rift++ source modules do not become JavaScript imports or RiftVM host imports.

## Effect system

Current supported effect vocabulary is exactly:
- storage
- repair_eval
- software_eval

Function effects are explicit with allow [...].

The compiler computes direct plus transitive required effects through the call graph.

It rejects:
- unknown effects
- duplicate effect names
- missing required effects
- unused/widened declared effects

This is exact-effect checking, not a permissive maximum-authority declaration.

### storage

checkpoint_save/load/remove lower only to:
- state.save
- state.load
- state.remove

Generated state schemas are canonical bounded descriptors.

Recursive checkpoint types are rejected.

### repair_eval

Current compiler built-ins:
- repair_input_source
- repair_expected_output
- repair_case_id
- repair_compile_test

They lower to fixed repair.* host imports.

### software_eval

Current compiler built-ins:
- software_input_source
- software_project_context
- software_specification
- software_case_id
- software_case_language
- software_compile_test

They lower to fixed software.* host imports.

## Activation boundary for effects

The compiler can produce executables with those imports and the focused tests can execute them with explicit test hosts.

The ordinary native RiftShell production path does not provide host.invoke and rejects every executable with imports before execution.

Therefore:
- the compiler effect system is live;
- general production shell storage/repair/software authority is not live merely because the compiler can lower those built-ins.

Any specialized host that executes these imports must be audited separately.

## Type/runtime safety relationship

Core performs source-level generic and type checking.

It always sends generated executable data through prepareRiftExecutable before compile succeeds.

RiftVM independently validates executable structure and runtime bounds.

A hand-authored .rxe does not inherit Core source-level type soundness, but malformed bytecode still cannot widen native authority through the VM.

## Determinism and bounded data

Vec capacity is compile-time fixed and <=256.

Composite runtime operations remain data-only.

value_sha256 lowers to a VM data primitive and introduces no host import.

Finite f64 rejects non-finite values; negative zero is canonicalized.

No implicit integer/f64 coercion exists.

## Deliberate bootstrap gaps

Not implemented include:
- top-level const
- for / loop execution
- bit operations
- field/index assignment
- nested/literal enum payload patterns
- dedicated arrays/slices
- ? propagation
- ownership/borrowing
- module privacy/export controls
- arbitrary FFI
- generic tensor/model/train language primitives
- self-hosted compiler

Unsupported syntax fails closed.

## Current proof status

Older README text tied device proof to previous Core versions/commits. That is historical and is not used as proof for the current 0.7.2 source tree.

This audit verifies current source/packaging/wiring only.

Current Builder/APK/device proof remains a separate gate.

## Critical invariants

- compiler version and docs stay aligned
- only bounded riftpp/1 source is accepted
- source modules close into one deterministic executable
- generated executable must pass RiftVM validation
- effects are exact and transitive
- compiler effect support never implies production host authority
- Vec remains <=256
- recursive/deep parser/linker/codegen inputs fail within explicit bounds
- unsupported syntax fails closed
- no eval/new Function/native shell code generation is introduced

## Failure signatures

- README says 0.7.0 while source exports 0.7.2 -> version drift
- docs say storage is the only effect -> effect-surface drift
- imported/effectful executable runs through ordinary riftpp shell -> host-boundary regression
- module dependency becomes ambient/unqualified -> linker regression
- extra dependency is silently accepted -> deterministic graph regression
- missing/extra effects compile -> exact-effect regression
- Vec capacity >256 -> resource regression
- compiler output bypasses prepareRiftExecutable -> validation regression
- historical Gate/device proof is presented as proof of current source -> trust regression
- headless QuickJS script constants become private to the nested `Scripts` object and the enclosing runtime can no longer compile -> Kotlin visibility regression

## Fix map

Lexer/parser/linker/type/effect/codegen -> src/riftpp-core.js.

VM validation/runtime -> src/riftvm.js.

Live QuickJS packaging/command host -> RiftHeadlessJsRuntime.kt.

RiftShell routing -> RiftNativeShell.kt.

Specialized repair/software/state execution hosts -> their owning subsystem, not Core.

## Validation

Second source audit must verify:
- 0.7.2 version
- Gradle packaging and QuickJS loading
- headless script constants remain visible to the enclosing `RiftHeadlessJsRuntime` while the `Scripts` object itself stays private
- public API
- source/token/type/module/effect/depth bounds
- module graph identity/cycle/unused dependency checks
- exact three-effect vocabulary
- transitive missing/extra effect rejection
- fixed repair/software/state import lowering
- production shell import rejection
- prepareRiftExecutable validation
- test coverage for current version/effects/modules/collections/numeric/string/state behavior

Node/Builder/device execution is a later global gate.
