# Semnexis QuickJS Bootstrap

## Status

**0.6 DEVICE VERIFIED / 0.6.1 HARDENING SOURCE VERIFIED / 0.7 SELF-HOSTING LEXER/PARSER SOURCE + MACHINE VERIFIED, DEVICE PROOF PENDING — 2026-09-19**

Semnexis is bootstrapped by a bounded JavaScript compiler hosted inside RiftOS headless QuickJS. No active Clang/GCC/CMake/LLD or raw subprocess compiler path exists.

Semnexis 0.6 is installed-device verified on RiftOS source `1d743b6fda2f5e7f1085186bc4bf6e9bbbd3ef36`. That device proof covers Native IR/`SNIRV0`, checked runtime ARM32 arithmetic, register allocation/spills, explicit CFG/phi conditionals and explicit-state loop backedges.

Current source is `0.7.0-quickjs-bootstrap`. The self-hosting pressure loop is source + independent-machine-regression verified through real `u8`, bounded generic type references, borrowed read-only `Slice<u8>`, native source scanning, flat immutable records, record-returning calls, field projection, record-valued conditional/loop merges, variable-width token spans, parser-state execution, and `u8`→`i32` widening for numeric literal values. Binary IR remains explicitly versioned from frozen `SNIRV0` through additive `SNIRV6`. Device promotion is still pending.

## Pipeline

```text
.snx
 -> bounded lexer/parser + AST structural budget
 -> Program Graph
 -> graph/effect/capability verification
 -> Execution Plan
 -> SEMNEXIS_NATIVE_IR_V0
 -> CFG/SSA + derived-effect verifier
 -> versioned binary IR encode/decode/reverify
    -> frozen SNIRV0 for i32 baseline
    -> SNIRV1 for u8 values
    -> SNIRV2 for borrowed Slice<u8>
    -> SNIRV3 for flat record values/calls/returns
    -> SNIRV4 for record field projection
    -> SNIRV5 for record-valued phi/control-flow merges
    -> SNIRV6 for verified u8-to-i32 widening
 -> ARM32 runtime lowering
    -> linear-scan-r4-r7-v0 for straight-line functions
    -> cfg-spill-v0 for CFG functions
    -> parallel phi-copy resolver
 -> canonical ELF32 / EM_ARM
 -> IR-bound machine-image verification
```

QuickJS remains only the bootstrap host.

## Fixed shell surface

```text
semx help
semx version
semx self-test
semx check <source.snx>
semx dump-graph <source.snx>
semx dump-plan <source.snx>
semx dump-ir <source.snx>
semx emit-arm32-proof <source.snx>
semx emit-arm32-runtime <source.snx>
```

Binary writes remain restricted to:
- `/documents/builds/Semnexis/semx-arm32-proof.elf`
- `/documents/builds/Semnexis/semx-arm32-runtime.elf`

Generated writable artifacts are not executed by RiftOS.

## Compatibility baseline

0.6.1 preserves the frozen existing proofs:
- smoke Program Graph: 12 nodes / 18 edges;
- smoke Execution Plan: 5 steps;
- smoke Native IR: 6 instructions;
- smoke `SNIRV0`: 157 bytes;
- straight-line add/call ELF: 192 bytes;
- full checked arithmetic ELF: 1016 bytes;
- shared software divider: 716 bytes;
- conditional `SNIRV0`: 674 bytes / ELF: 340 bytes / 4 blocks;
- loop `SNIRV0`: 756 bytes / ELF: 368 bytes / 4 blocks.

`SNIRV0` version 0 remains a frozen compatibility surface. `SNIRV1` adds `u8`; `SNIRV2` adds borrowed `Slice<u8>`; `SNIRV3` adds flat-record schemas plus construction/copy/return/call ABI; `SNIRV4` adds record field projection; `SNIRV5` adds record-valued phi merges; `SNIRV6` adds verified `u8`→`i32` widening for numeric parsing. The automatic encoder selects the oldest format that can represent the verified IR, while explicit older encoders reject newer semantics. Unknown binary versions, reserved flags, capability flags and opcodes are rejected.

Function/parameter/instruction `graphNode` fields are deterministic Program Graph **correlation IDs**. V0 does not cryptographically bind a serialized IR image to a specific Program Graph, so those IDs are advisory cross-layer metadata rather than independent provenance proof.

## Hardening gate 0.6.1

### Derived effects and capabilities

Native IR verification no longer trusts serialized effect/capability labels. It re-derives direct `clock()` effects and transitive function-call effects, then requires stored effect/requirement metadata to match those facts. Forged `pure` metadata is rejected.

### Canonical ARM32 verification

The public runtime verifier now requires the originating verified Native IR. It structural-verifies the ELF, re-emits the canonical machine image from the IR, and compares the complete byte image plus function/block metadata. Single-byte arithmetic or branch mutation is rejected.

Builder also runs an independent strict ARM32 instruction-subset interpreter over emitted programs. It executes checked arithmetic, signed division, all six comparisons, calls, loops, traps and cyclic-phi copies. Unknown machine instructions fail closed.

### Parallel phi copies

CFG phi edge copies use a true parallel-copy resolver. Acyclic moves emit directly; copy cycles preserve one predecessor value in `r12` before completing the cycle. A real two-phi swap is covered by machine-execution regression.

### Resource and complexity budgets

Compiler/host limits include:
- Semnexis source: 512 KiB;
- tokens: 65,536;
- functions: 1,024;
- recursive/structural expression depth: 256;
- generic type-reference nesting depth: 16;
- Program Graph nodes: 65,536;
- Program Graph edges: 262,144;
- CFG blocks/function: 512;
- runtime ARM32 artifact: 1 MiB;
- `semx` captured command output: 256 KiB.

The Program Graph maintains an indexed outgoing-edge view for normal operation, but `verify()` rebuilds that index from the authoritative edge array before validation. Long runtime call-cycle analysis is iterative rather than recursive.

Graph/plan/IR dumps are lazy and support construction-time output budgets rather than building unbounded strings first.

## 0.7 self-hosting pressure loop

The 0.7 work is driven by attempting to express real compiler pieces in Semnexis and fixing only general language/runtime blockers exposed by those attempts.

The first lexer pressure pass proved:
- ASCII classification logic already compiled with the 0.6 scalar/control-flow surface;
- honest byte parameters required a real `u8` type instead of pretending bytes are `i32`;
- `u8` values use zero-extended 32-bit register representation and select `SNIRV1`;
- generic type-reference syntax now parses bounded `Name<T,...>` forms, while unsupported generic semantics still fail closed;
- a source buffer required a real bounded borrowed view, implemented as read-only `Slice<u8>`;
- `Slice<u8>` is parameter/local-only and cannot escape through function returns;
- its ABI is one descriptor pointer to a 4-byte-aligned two-word record: data pointer at offset 0 and nonnegative signed-`i32` length at offset 4;
- `slice_len(slice)` is pure and validates descriptor/length;
- `slice_get(slice,index)` is pure, validates descriptor, nonnegative index/length, bounds and non-null data before emitting `LDRB`;
- slice IR selects `SNIRV2`; V0/V1 reject it;
- flat immutable records support up to four `i32`/`u8` fields with a Semnexis-owned `flat_words_v0` ABI;
- record values support construction, copy, direct return, record-returning calls and r0-r3 multiword returns through `SNIRV3`;
- field projection lowers to verified `record.get` and selects `SNIRV4`;
- record-valued `if` expressions lower to `phi.record`, select `SNIRV5`, and copy aggregate fields through the same hardened parallel edge-copy resolver used by scalar CFG phis;
- flat records can be carried through explicit-state loop phis while preserving exact record type across `next(...)`;
- arithmetic context widens zero-extended `u8` values to `i32` through verified `zext.u8.i32`, selecting `SNIRV6`.

The independent ARM execution regression seeds real descriptor/data memory and proves successful reads plus null-descriptor, negative-index, out-of-range, negative-length and null-data traps.

A real self-hosting scan kernel now compiles and executes natively:

```text
Slice<u8>
 -> slice_len
 -> loop/backedge
 -> slice_get
 -> u8 classifier
 -> checked count
```

The machine regressions scan `a1b23!` and return digit count `3`, construct/return a real `Token { kind,start,end }`, preserve that record across native calls and loop backedges, project fields, execute record-valued branch merges, scan variable-width numeric spans (`123+4` → `[0,3)`, `[3,4)`, `[4,5)`), run a streaming parser-state kernel that accepts `1+2` and rejects malformed forms, and accumulate decimal bytes (`1234` → integer `1234`). The next pressure target is structured parse/AST output and its storage model—not another guessed runtime feature.

## CFG and loops

Native IR control flow includes:
- `block.begin`;
- unconditional `br`;
- signed `br.cmp.eq/ne/lt/le/gt/ge`;
- `phi.i32`;
- `phi.record` for flat-record conditional merges.

The verifier checks reachability, predecessor/successor sets, terminators, phi placement/incoming edges, dominators and SSA dominance.

Explicit-state loops preserve simultaneous `next(...)` semantics. Header phis represent current iteration state and the body emits a real backward branch to the header.

CFG functions currently use conservative `cfg-spill-v0`; straight-line functions retain `linear-scan-r4-r7-v0`.

## Failure signatures

Invalid states include:
- forged effect/capability metadata;
- malformed/unreachable CFG blocks;
- invalid predecessor/dominator/phi relationships;
- cyclic phi copies lowered as destructive sequential moves;
- runtime ELF bytes differing from canonical IR lowering;
- unknown `SNIRV0` versions/flags/opcodes;
- source/token/AST/graph/CFG/artifact/output budget escape;
- recursive runtime call cycles;
- generated artifact authority escaping the two fixed paths;
- generated writable artifacts being executed.

## Source ownership

- `src/semnexis-bootstrap.js`: frontend, Program Graph, Native IR/CFG/effect verifier, `SNIRV0`, ARM32 lowering and canonical machine verifier.
- `RiftHeadlessJsRuntime.kt`: bounded QuickJS host, Semnexis source/output limits and exact-path artifact writer.
- `RiftNativeShell.kt`: fixed `semx` route.
- `scripts/test-semnexis-bootstrap.mjs`: semantic/IR/binary/adversarial hardening regressions.
- `scripts/test-semnexis-arm32-exec.mjs`: independent ARM32 machine execution regression.
- `scripts/test-semnexis-shell.mjs`: shell/authority contract plus exact embedded command self-test execution.
- `scripts/validate-rift-wiring.mjs`: architecture guard.

## Next APK gate

The next APK must prove:
- compiler `0.7.0-quickjs-bootstrap`;
- self-test `semnexis-bootstrap-self-test/13`;
- frozen baseline `SNIRV0` remains version 0 / 157-byte smoke;
- latest binary format reports `SNIRV6` / version 6;
- all inherited 0.6 arithmetic/CFG/loop fields;
- `sliceIrBinaryFormat=SNIRV2`, `sliceIrBinaryBytes=273`, `sliceV1Rejects=true`;
- `recordIrBinaryFormat=SNIRV3`, `recordIrBinaryBytes=358`, `recordV2Rejects=true`;
- `projectionIrBinaryFormat=SNIRV4`, `projectionIrBinaryBytes=802`, `projectionV3Rejects=true`;
- `recordConditionalIrBinaryFormat=SNIRV5`, `recordConditionalIrBinaryBytes=1125`, `recordConditionalV4Rejects=true`, `recordConditionalPhiCount=2`;
- `recordLoopIrBinaryFormat=SNIRV5`, `recordLoopIrBinaryBytes=1554`, `recordLoopV4Rejects=true`, with one record phi plus one scalar phi in `count_tokens`;
- `decimalIrBinaryFormat=SNIRV6`, `decimalIrBinaryBytes=1085`, `decimalV5Rejects=true`, `decimalZextCount=1`;
- `arm32SliceBytes=232`, `arm32RecordBytes=248`, `arm32ProjectionBytes=440`, `arm32RecordConditionalBytes=644`, `arm32RecordLoopBytes=816`, `arm32DecimalBytes=532`;
- record-conditional function metadata remains 512-byte body / 128-byte frame / 32 slots / 17 spills / 7 CFG blocks / `cfg-spill-v0`;
- `hardeningDerivedEffects=true`;
- `hardeningCanonicalMachineVerify=true`;
- `hardeningExpressionBudget=true`;
- fixed 0.6 conditional/loop artifacts remain unchanged.

## Fix map

Frontend/parser/Program Graph/Native IR/`SNIRV0`/ARM32 verifier or lowering → `src/semnexis-bootstrap.js`.

Headless QuickJS source/output/artifact bounds and fixed binary writes → `android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt`.

Native shell routing → `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt`.

Semantic/binary/adversarial regressions → `scripts/test-semnexis-bootstrap.mjs`.

Independent machine behavior → `scripts/test-semnexis-arm32-exec.mjs`.

Embedded host contract → `scripts/test-semnexis-shell.mjs`.

Architecture/package wiring → `scripts/validate-rift-wiring.mjs` and Builder APK verification.

## Validation

Source promotion requires:
- frozen smoke graph/plan/IR/`SNIRV0` compatibility;
- version/flags/opcode binary rejection tests;
- checked signed-i32 arithmetic/trap semantics;
- direct/transitive effect/capability forgery rejection;
- CFG/SSA/dominator/phi contracts;
- cyclic parallel-phi machine execution;
- complete canonical machine-image comparison;
- independent ARM32 behavior execution;
- compiler/AST/graph/CFG/artifact/output budgets;
- long call-chain handling without recursive verifier stack use;
- exact embedded `semx self-test` execution in Builder;
- source/compiler asset package parity;
- no generated-artifact execution authority.

Build/device promotion remains separate from source verification.
