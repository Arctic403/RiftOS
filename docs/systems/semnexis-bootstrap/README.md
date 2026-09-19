# Semnexis QuickJS Bootstrap

## Status

**0.4 DEVICE VERIFIED / 0.6 CFG + EXPLICIT-STATE LOOPS SOURCE VERIFIED, DEVICE PROOF PENDING — 2026-09-19**

Semnexis is bootstrapped by a bounded JavaScript compiler hosted inside RiftOS headless QuickJS. No active Clang/GCC/CMake/LLD or raw subprocess compiler path exists.

Semnexis 0.4 is installed-device verified on RiftOS source `11cd28ce599fc0455a25a630c53fd1f6f93b926e`.

Current source is `0.6.0-quickjs-bootstrap`. It preserves the 0.5 checked ARM32 arithmetic/register-allocation proofs and adds source-verified explicit CFG conditionals and explicit-state loops.

## Pipeline

```text
.snx
 -> Program Graph
 -> graph/effect/capability verification
 -> Execution Plan
 -> SEMNEXIS_NATIVE_IR_V0
 -> CFG/SSA verifier
 -> optional SNIRV0 encode/decode/reverify
 -> ARM32 runtime lowering
    -> linear-scan-r4-r7-v0 for straight-line functions
    -> cfg-spill-v0 for CFG functions
 -> ELF32 / EM_ARM
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

## Compatibility and arithmetic

0.6 preserves:
- smoke graph 12/18;
- 5-step smoke plan;
- 6-instruction smoke IR;
- 157-byte smoke `SNIRV0`;
- 192-byte register-allocated add/call ELF;
- 1016-byte checked add/sub/mul/div ELF;
- 716-byte software divider.

The straight-line allocator remains `linear-scan-r4-r7-v0`.

## CFG and phi

0.6 Native IR adds:
- `block.begin`;
- unconditional `br`;
- signed `br.cmp.eq/ne/lt/le/gt/ge`;
- `phi.i32`.

The verifier checks reachability, predecessors, terminators, phi placement/incoming edges, dominators and SSA dominance.

`SNIRV0` serializes and independently reverifies these control-flow facts.

### Conditional fixture

```snx
fn choose(a: i32, b: i32) -> i32 {
    return if a < b { a + 1 } else { b + 2 };
}
```

Source proof:
- `SNIRV0` 674 bytes;
- ELF 340 bytes;
- 4 blocks;
- all six signed comparisons route to the correct named ARM block;
- both branch predecessors perform phi edge copies before merge;
- nested conditionals: 7 blocks / 444-byte ELF.

## Explicit-state loops

```snx
fn sum(n: i32) -> i32 {
    return loop (i = 0, acc = 0) while i < n {
        next (i + 1, acc + i);
    } yield acc;
}
```

Carried loop state is explicit semantic state, not hidden mutable locals. Header phis represent current state, `next(...)` values update simultaneously, and the body backedge feeds those values into the phis.

Source proof:
- `SNIRV0` 756 bytes;
- ELF 368 bytes;
- 4 blocks;
- two initialization phi copies;
- signed header condition;
- false edge to exit;
- real backward ARM branch to header;
- two backedge phi copies.

CFG functions currently use conservative `cfg-spill-v0`; modules containing both CFG and straight-line functions report `mixed-v0`.

## Failure signatures

Invalid states include:
- malformed/unreachable blocks;
- incorrect CFG predecessor sets;
- phi incoming labels not matching predecessors;
- SSA use not dominated by its definition;
- wrong signed comparison branch encoding/target;
- missing phi edge copy;
- loop backedge not targeting the header;
- loop next-state count mismatch;
- generated artifact authority escaping the two fixed paths;
- generated writable artifacts being executed.

## Source ownership

- `src/semnexis-bootstrap.js`: frontend, Program Graph, Native IR/CFG verifier, `SNIRV0`, ARM32 backend.
- `RiftHeadlessJsRuntime.kt`: bounded QuickJS host and exact-path artifact writer.
- `RiftNativeShell.kt`: fixed `semx` route.
- `scripts/test-semnexis-bootstrap.mjs`: semantic/IR/backend/CFG/loop regressions.
- `scripts/test-semnexis-shell.mjs`: shell/authority contract.
- `scripts/validate-rift-wiring.mjs`: architecture guard.

## Next APK gate

The next APK must prove:
- compiler `0.6.0-quickjs-bootstrap`;
- self-test `semnexis-bootstrap-self-test/6`;
- inherited 0.5 arithmetic/register-allocation fields;
- conditional `SNIRV0` 674 / ELF 340 / 4 blocks / `cfg-spill-v0`;
- loop `SNIRV0` 756 / ELF 368 / 4 blocks / `cfg-spill-v0`;
- controlFlowLowered=true;
- real ARM loop backedge=true;
- both fixed control-flow fixtures can be emitted through `semx emit-arm32-runtime`.

## Fix map

Frontend syntax, Program Graph construction, effect/capability solving, Native IR, `SNIRV0`, CFG/SSA verification and ARM32 lowering → `src/semnexis-bootstrap.js`.

Headless QuickJS capability boundaries, source reads, fixed binary-output paths, atomic artifact writes and embedded `semx` command entry → `android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt`.

Native shell routing for `semx` → `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt`.

Compiler, IR, binary-format, ARM32, CFG, comparison and loop regressions → `scripts/test-semnexis-bootstrap.mjs`.

Shell/host authority and packaging contract → `scripts/test-semnexis-shell.mjs` and `scripts/validate-rift-wiring.mjs`.

APK packaging parity for the compiler asset → Builder `scripts/verify-riftos-apk.sh`.

## Validation

Source validation for this subsystem must verify:
- frozen smoke graph/plan/IR and `SNIRV0` compatibility expectations;
- checked signed-i32 arithmetic and overflow/division trap semantics;
- function ABI, liveness/register allocation and spill-frame invariants;
- CFG reachability, predecessor sets, terminators, dominators, SSA dominance and phi predecessor contracts;
- all six signed comparison branches target the intended blocks;
- explicit-state loop initialization, simultaneous `next(...)` semantics and real backward branch generation;
- `SNIRV0` encode/decode/reverify rejects malformed, truncated, unknown-opcode and trailing-byte payloads;
- headless QuickJS exposes only bounded RiftFS reads plus the two fixed Semnexis binary-output paths;
- generated writable artifacts remain non-executable from RiftFS;
- packaged `assets/www/src/semnexis-bootstrap.js` matches source byte-for-byte.

Build/device promotion remains separate from source verification. A promoted APK must pass the Builder source checks, Gradle validation/compilation, APK packaging/signing checks, `semx self-test`, and the documented device fixtures before source-only 0.6 claims are upgraded to device-verified claims.
