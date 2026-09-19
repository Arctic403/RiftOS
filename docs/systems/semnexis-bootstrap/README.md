# Semnexis QuickJS Bootstrap

## Status

**0.6 DEVICE VERIFIED / 0.6.1 HARDENING SOURCE VERIFIED, DEVICE PROOF PENDING — 2026-09-19**

Semnexis is bootstrapped by a bounded JavaScript compiler hosted inside RiftOS headless QuickJS. No active Clang/GCC/CMake/LLD or raw subprocess compiler path exists.

Semnexis 0.6 is installed-device verified on RiftOS source `1d743b6fda2f5e7f1085186bc4bf6e9bbbd3ef36`. That device proof covers Native IR/`SNIRV0`, checked runtime ARM32 arithmetic, register allocation/spills, explicit CFG/phi conditionals and explicit-state loop backedges.

Current source is `0.6.1-quickjs-bootstrap`. It is a hardening-only candidate: no new language feature surface was added.

## Pipeline

```text
.snx
 -> bounded lexer/parser + AST structural budget
 -> Program Graph
 -> graph/effect/capability verification
 -> Execution Plan
 -> SEMNEXIS_NATIVE_IR_V0
 -> CFG/SSA + derived-effect verifier
 -> optional SNIRV0 encode/decode/reverify
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

`SNIRV0` version 0 is now treated as a frozen compatibility surface. Unknown binary versions, reserved flags, capability flags and opcodes are rejected. New incompatible binary semantics must use an explicit future format revision.

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
- Program Graph nodes: 65,536;
- Program Graph edges: 262,144;
- CFG blocks/function: 512;
- runtime ARM32 artifact: 1 MiB;
- `semx` captured command output: 256 KiB.

The Program Graph maintains an indexed outgoing-edge view for normal operation, but `verify()` rebuilds that index from the authoritative edge array before validation. Long runtime call-cycle analysis is iterative rather than recursive.

Graph/plan/IR dumps are lazy and support construction-time output budgets rather than building unbounded strings first.

## CFG and loops

Native IR control flow includes:
- `block.begin`;
- unconditional `br`;
- signed `br.cmp.eq/ne/lt/le/gt/ge`;
- `phi.i32`.

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
- compiler `0.6.1-quickjs-bootstrap`;
- self-test `semnexis-bootstrap-self-test/7`;
- all inherited 0.6 arithmetic/CFG/loop fields;
- `hardeningDerivedEffects=true`;
- `hardeningCanonicalMachineVerify=true`;
- `hardeningExpressionBudget=true`;
- fixed conditional/loop artifact emission remains unchanged.

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
