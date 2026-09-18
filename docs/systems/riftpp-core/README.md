# Rift++ Core Bootstrap Frontend

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-18.**

Current source is a **0.10 native-byte-substrate candidate**, not yet Builder/device-proven.

## Purpose

`src/riftpp-core.js` is the packaged Rift++ bootstrap compiler frontend.

It accepts bounded `riftpp/1` source, builds source-spanned syntax, performs semantic/type/control-flow/effect checks, links bounded modules, and lowers successful programs to `rift-exec-v1 / riftvm-1`.

Current compiler implementation candidate:
`0.10.0-bootstrap`.

Frozen compatibility oracle:
`0.7.2-bootstrap`.

The source language remains `riftpp/1`; Gate 1A/1B additions are additive.

## Live activation

Gradle packages:
- `src/riftpp-core.js`;
- `src/riftvm.js`.

`RiftHeadlessJsRuntime` loads both into bounded QuickJS for native RiftShell `riftpp` commands and fixed developer verification tools.

Normal `riftpp run/exec` rejects all host imports.
`run-stateful/exec-stateful` permits only:
- `state.load`;
- `state.save`;
- `state.remove`.

Repair/software imports remain specialized-host-only.

## Source ownership

Primary frontend:
- `src/riftpp-core.js`

VM validator/executor:
- `src/riftvm.js`

Android host:
- `RiftHeadlessJsRuntime.kt`
- `RiftNativeShell.kt`

Focused validation:
- `scripts/test-rift-plus-plus-core-v1.mjs`
- `scripts/test-rift-vm.mjs`
- `scripts/test-riftpp-shell.mjs`
- `scripts/validate-rift-wiring.mjs`

## Public compiler API

`globalThis.RiftPlusPlusCore` exposes:
- version;
- language;
- targetFormat;
- targetAbi;
- lex;
- parse;
- compile;
- compileProgram;
- inspect;
- inspectProgram.

The compiler module has no filesystem, network, Android, MCP or process authority.

## Native byte substrate

The 0.10 local candidate adds:
- checked primitive `u8` in range 0..255;
- contextually typed u8 literals with compile-time range rejection;
- existing `Buffer<T,N>` / `Slice<T>` instantiated as `Buffer<u8,N>` / `Slice<u8>`;
- explicit lossless `u8_to_u32`;
- checked `u8_from_u32`;
- checked u8 arithmetic/comparison;
- deterministic hash/display/state serialization for u8.

Indexes and lengths remain `u32`.

There is no implicit u8/u32 coercion and no raw pointer primitive.

Runtime proof is pending Builder/install; focused source tests and wiring validation already encode the required positive/negative contract.

## Current frontend/resource bounds

- source: 256 KiB UTF-8 boundary size per module;
- tokens: 50,000;
- functions: 256;
- parameters/function: 64;
- locals/function: 512;
- struct fields / enum payload/cases: 64;
- named local types: 256;
- Vec capacity: 1..256;
- Buffer capacity: 1..100000;
- SourceText: <=4,194,304 UTF-16 code units;
- StringBuilder: <=4,194,304 UTF-16 code units;
- generic/type nesting: 32;
- parser/linker/codegen guarded depth families: 128;
- use declarations/module: 64;
- linked modules: 64;
- aggregate linked source: 1 MiB;
- linked symbol name: 96 UTF-8 bytes;
- declared effects/function: 16;
- checkpoint schema: 4096 UTF-8 bytes.

## Language surface

Frozen/current core constructs include:
- module + bounded `use ... as ...`;
- typed functions and unit `main`;
- `unit`, `bool`, `u32`, `s32`, finite `f64`, compatibility `string`;
- `let` / `var`;
- checked assignment/compound assignment;
- lexical scopes;
- `if/else`, `while`, `break`, `continue`, `return`;
- return-completeness/unreachable checking;
- short-circuit `and/or/not`;
- nominal structs/enums;
- exhaustive match + guards;
- `Option` / `Result`;
- `Vec<T,N>`;
- checked arithmetic;
- compatibility `string_len/find/slice/replace`;
- `value_sha256`;
- checkpoint builtins;
- repair/software-eval compiler surfaces;
- print.

Gate 1A adds, and is device-frozen:
- persistent `Buffer<T,N>`, max 100000;
- read-only zero-copy `Slice<T>`;
- Buffer/Slice checkpoint denial.

Gate 1B source candidate adds:
- `SourceText`;
- `TextCursor`;
- `StringBuilder<N>`;
- `source_text(string)`;
- SourceText `code_unit_len()`, `utf8_byte_len()`, `cursor()`, `slice()`, `to_string()`;
- TextCursor `code_unit_offset()`, `line()`, `column()`, `eof()`, `peek_code_unit()`, `advance()`;
- StringBuilder `len_units()`, `append()`, `append_source()`, `finish()`;
- `parse_u32/s32/f64`;
- `format_u32/s32/f64`.

`for`, `loop` and top-level `const` remain recognized/reserved but not implemented by this bootstrap.

## Text model

Gate 1B deliberately separates hot representation from interchange encoding.

Hot runtime/compiler working text:
- UTF-16 code units;
- SourceText slices use code-unit offsets;
- TextCursor advances one code unit;
- StringBuilder capacity is measured in code units.

Explicit boundary accounting:
- `SourceText.utf8_byte_len()` using canonical UTF-8 replacement semantics: an unpaired UTF-16 surrogate contributes the UTF-8 encoding of U+FFFD (3 bytes), independent of host/JVM encoder behavior;
- the headless `TextEncoder` bridge uses the same canonical encoder and normalizes bridged signed bytes into an unsigned `Uint8Array`, so hashing, byte limits, fixture hashes and benchmark decoding share one deterministic boundary.
- file/protocol/tokenizer/hash/provenance layers may continue to use UTF-8 bytes where their own contracts require them.

This preserves existing `riftpp/1` JavaScript UTF-16 code-unit semantics while avoiding mandatory UTF-8 transcoding in the hot lexer/parser representation.

The fixed `rift-tool text-model-benchmark` v2 measures two separate workloads: lexer-like sequential traversal, and random UTF-16-code-unit access using either direct UTF-16 storage or UTF-8 bytes plus a code-unit index. It also records UTF-8 encode/index preparation cost and index-memory overhead. It records measurements; it does not hardcode a winner or claim universal encoding superiority.

## Gate 1A storage semantics

`Buffer<T,N>`:
- compile-time capacity <=100000;
- persistent value semantics;
- current VM implementation uses a structurally shared 32-way trie;
- get/push/set/slice;
- old aliases remain unchanged.

`Slice<T>`:
- read-only view over one immutable Buffer version;
- len/get;
- remains stable after later Buffer versions are produced.

Neither Buffer nor Slice may enter checkpoint state in Gate 1A.

## Gate 1B working-text safety

`SourceText`, `TextCursor` and `StringBuilder`:
- are compiler/runtime working values;
- do not gain checkpoint persistence;
- do not cross generic host imports;
- are denied by `value_sha256` until a separately specified canonical projection exists.

Compatibility `string` remains separately bounded to 65,536 UTF-8 bytes by RiftVM public/runtime limits.

## Numeric text semantics

Gate 1B numeric parsing:
- returns `Result` for invalid/out-of-range user text;
- u32/s32 range checks remain exact;
- f64 rejects invalid/non-finite values.

Formatting is deterministic in the active VM:
- integer decimal;
- finite f64 canonical form including explicit decimal point;
- exponent normalization such as `1.0e-7`.

## Effect system

Supported language effects remain exactly:
- `storage`;
- `repair_eval`;
- `software_eval`.

Effects are exact/transitive requirements, not permissive ambient authority.

Compiler support for an effect never implies that ordinary RiftShell grants the associated host capability.

## Validation/oracle split

`rift-tool gate0-verify`
- archival exact-reference / frozen-baseline drift suite.

`rift-tool semantic-compat`
- current compiler/runtime against frozen semantic compatibility behavior.

`rift-tool text-model-benchmark`
- fixed, sandboxed installed-device representation benchmark;
- no generic JavaScript/process/network/filesystem authority.

## Current proof state

Frozen:
- Gate 0.1;
- Gate 0.2;
- Gate 1A.

Gate 1B source-side proof currently includes:
- functional UTF-16/code-unit SourceText/cursor/builder execution;
- explicit UTF-8 boundary length accounting;
- half-surrogate/code-unit edge behavior;
- 70,000-code-unit builder;
- numeric parse/format positives and failures;
- checkpoint/hash denials;
- full frozen semantic suite PASS;
- RiftLLM+ consumer regression compile PASS.

Still required before Gate 1B freeze:
1. Builder;
2. exact installed source SHA;
3. `rift-tool semantic-compat`;
4. exact Gate 1B fixtures on-device;
5. `rift-tool text-model-benchmark`;
6. `riftpp self-test`;
7. authority-boundary regression;
8. final audit.

## Deliberate bootstrap gaps

Still not implemented:
- top-level const execution;
- for/loop execution;
- deterministic Map/Set;
- deterministic serializer/data writer;
- compiler/toolchain execution profile;
- bit operations;
- field/index assignment syntax;
- general user generics;
- module privacy/export;
- ownership/borrowing;
- arbitrary FFI;
- self-hosted compiler;
- native machine backend.

Unsupported features fail closed.

## Critical invariants

- source language remains `riftpp/1` unless explicitly versioned;
- old valid `riftpp/1` behavior is not silently reinterpreted;
- Vec remains <=256;
- Buffer remains <=100000;
- SourceText/StringBuilder bounds remain explicit;
- hot text uses specified code-unit behavior;
- UTF-8 boundary accounting is explicit;
- working-text values do not gain state/hash/host authority;
- generated executable always passes RiftVM validation;
- effects remain exact;
- ordinary shell host-import denial remains intact;
- compiler/VM/docs/tests remain version-synchronized.

## Failure signatures

Use these signatures to route failures to the owning layer before changing validation:

- `validate-rift-docs.mjs` reports a missing maintenance heading -> documentation-contract regression; restore the required README section rather than weakening the validator.
- current Core lowers a Gate 1B opcode but `prepareRiftExecutable` rejects it -> compiler/VM instruction-normalization desync; update the VM allowlist and focused Core/VM tests together.
- `rift-tool semantic-compat` fails while the archival `gate0-verify` identity remains intact -> current implementation changed frozen observable semantics; classify as an accidental regression or an explicit versioned migration.
- Gate 1B SourceText/TextCursor/StringBuilder tests fail while compatibility string tests pass -> working-text implementation regression; do not alter frozen `string_len/find/slice/replace` behavior to compensate.
- `rift-tool text-model-benchmark` does not return schema `riftpp-text-model-benchmark-v2` with status `MEASURED` -> fixed benchmark/tool-host wiring regression; do not replace it with arbitrary script execution.
- normal `riftpp run` accepts state/repair/software imports -> host-authority regression and release blocker.
- stateful execution accepts repair/software imports -> capability-boundary regression and release blocker.
- packaged Core/VM hashes differ from source or installed source SHA differs from the candidate commit -> provenance/package regression; do not treat runtime results as promotion evidence.
- Builder source-check fails on a current version/opcode assertion -> inspect the first failing contract and update stale validation only when live code proves the new contract.

## Fix map

Frontend syntax/type/effect/lowering:
`src/riftpp-core.js`

VM validation/execution/text representation:
`src/riftvm.js`

QuickJS/device verifier + fixed benchmark:
`RiftHeadlessJsRuntime.kt`

Shell routing/help:
`RiftNativeShell.kt`

## Validation

Current source audit must verify:
- compiler candidate `0.10.0-bootstrap`;
- VM ABI `riftvm-1`;
- Vec-256 / Buffer-100000;
- UTF-16 SourceText/TextCursor/StringBuilder bounds and opcodes;
- explicit UTF-8 boundary op;
- numeric parse/format ops;
- u8 primitive/conversion/compiler+VM verifier contract;
- frozen semantic compatibility;
- fixed benchmark route without generic authority;
- module/effect/resource guards;
- production import denial;
- Builder/device proof before Gate 1B freeze.
