# Semnexis QuickJS Bootstrap

## Status

**0.1 SEMANTIC BOOTSTRAP DEVICE VERIFIED / 0.3 NATIVE IR + ARM32 BACKEND DEVICE PROOF PENDING — 2026-09-19**

Semnexis is bootstrapped by a bounded JavaScript compiler hosted inside RiftOS headless QuickJS. Clang, GCC, CMake, LLD and raw subprocess execution are not part of the active bootstrap.

The installed 0.1 path was device-verified on RiftOS source `a7ba2b211b22fedf53073f6724d14305fec01c87`: `semx self-test`, smoke compilation, Program Graph golden and Execution Plan golden passed on the Android target.

The current source extends that compiler to 0.3 with verified Native IR, binary IR serialization and a bounded ARM32 ELF proof backend. Those new 0.3 features require the next APK before device verification is claimed.

Current source pipeline:

```text
.snx source in RiftFS
    -> RiftShell semx
    -> RiftHeadlessJsRuntime
    -> packaged semnexis-bootstrap.js
    -> lexer/parser
    -> deterministic Program Graph
    -> graph verifier
    -> effect/capability solver
    -> Execution Plan
    -> SEMNEXIS_NATIVE_IR_V0
    -> Native IR verifier
    -> SNIRV0 binary encode/decode/reverify
    -> ARM32 proof backend (pure zero-input V0 only)
    -> ELF32 / EM_ARM artifact
```

QuickJS is a bootstrap host, not the target runtime for Semnexis programs and not the intended self-hosted compiler runtime.

## Bootstrap surface

RiftShell exposes fixed Semnexis commands:

```text
semx help
semx version
semx self-test
semx check <source.snx>
semx dump-graph <source.snx>
semx dump-plan <source.snx>
semx dump-ir <source.snx>
semx emit-arm32-proof <source.snx>
```

`emit-arm32-proof` writes only `/documents/builds/Semnexis/semx-arm32-proof.elf`.

The Semnexis host exposes no subprocess authority, raw Android shell or network authority. Generated writable RiftFS artifacts are never executed by this path.

## Semantic and IR contract

The bootstrap currently owns:
- V0 lexer/parser for functions, parameters, locals, integer arithmetic and calls;
- canonical signed `i32` facts;
- deterministic Program Graph and graph verifier;
- pure/time effect inference and explicit time capabilities;
- deterministic Execution Plan;
- `SEMNEXIS_NATIVE_IR_V0` typed SSA-like lowering;
- explicit regions/effects/capabilities in IR;
- Program Graph provenance on IR functions, parameters and instructions;
- IR use-before-definition, canonical SSA identity, call-arity and structure verification;
- checked `i32` add/sub/mul/div semantics;
- canonical binary IR format `SNIRV0`;
- binary decode followed by independent IR re-verification.

V0 checked arithmetic is language semantics. Backends may not replace it with target-specific undefined overflow behavior.

## ARM32 proof backend

The first direct backend consumes verified Native IR and emits `SEMNEXIS_ARM32_ELF_PROOF_V0`.

Current target:
- `armv7a-linux-androideabi26`;
- ELF32 little-endian;
- `EM_ARM`;
- ARM EABI5;
- one RX `PT_LOAD` segment.

The proof backend currently accepts only pure, capability-free programs whose `main` has no runtime parameters. Current V0 programs are evaluated from verified IR at build time with checked `i32` semantics, then a deterministic ARM executable image is emitted. Calls and locals are supported. Effects, runtime inputs, recursion and unsupported operations fail closed.

The smoke/native-call proof is 100 bytes and returns 42. This is a real native backend seed, not yet the general runtime-valued backend required to compile the Semnexis compiler itself.

## Source ownership

Maintained live owners:
- `src/semnexis-bootstrap.js` — compiler, Program Graph lowering, Native IR, binary IR codec and ARM32 proof backend;
- `android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt` — bounded QuickJS host, confined source read and fixed Semnexis binary artifact writer;
- `android/app/src/main/java/com/riftos/app/RiftNativeShell.kt` — fixed `semx` routing/help surface;
- `android/app/build.gradle.kts` — packages the bootstrap source into APK assets;
- `scripts/test-semnexis-bootstrap.mjs` — semantic/IR/binary/backend regression suite;
- `scripts/test-semnexis-shell.mjs` — host/shell/packaging/authority boundary regression suite.

The language-project mirror is `workspace/Semnexis/bootstrap/quickjs/semnexis-bootstrap.js`. During bootstrap it must remain byte-identical to RiftOS `src/semnexis-bootstrap.js`.

## Failure signatures

The bootstrap is invalid if:
- `semx` routes through WebView or a generic JS evaluator;
- Semnexis source escapes RiftFS confinement;
- the host gains process, shell or network authority;
- the fixed binary writer can write outside `/documents/builds/Semnexis/semx-arm32-proof.elf`;
- `RiftNativeToolchain`, `riftclang` or a Clang payload returns;
- identical source produces different graph/plan/IR output;
- malformed graph or Native IR passes verification;
- `SNIRV0` corrupted/trailing/malformed input is accepted;
- checked `i32` behavior differs across compiler/backend paths;
- effectful code is accepted by the current pure-only ARM32 proof backend;
- generated writable artifacts are executed from RiftFS;
- the packaged bootstrap asset is missing or differs from source.

## Fix map

- lexer/parser/Program Graph/solvers -> `src/semnexis-bootstrap.js`;
- Native IR verifier/codec -> `src/semnexis-bootstrap.js`;
- ARM32 proof backend/ELF verifier -> `src/semnexis-bootstrap.js`;
- QuickJS host/confined I/O -> `RiftHeadlessJsRuntime.kt`;
- shell command surface -> `RiftNativeShell.kt`;
- APK asset packaging -> `android/app/build.gradle.kts`;
- compiler/backend regressions -> `scripts/test-semnexis-bootstrap.mjs`;
- shell/authority regressions -> `scripts/test-semnexis-shell.mjs`;
- language roadmap/status -> `workspace/Semnexis`.

## Validation

Source promotion requires:
- bootstrap smoke graph and plan goldens;
- frozen Native IR golden;
- deterministic `SNIRV0` binary encoding;
- binary decode/reverify round-trip;
- corrupted binary rejection;
- invalid source/capability/range rejection;
- ARM32 ELF magic/class/machine/program-header/code verification;
- pure multi-function native proof returns 42;
- effectful backend input rejection;
- source mirror hashes match;
- full RiftOS and Semnexis audits add no new findings.

Next APK device gate:
- `semx self-test` reports compiler `0.3.0-quickjs-bootstrap`;
- self-test reports 6 IR instructions, 157-byte `SNIRV0` and 100-byte ARM32 ELF proof;
- `semx dump-ir tests/smoke.snx` matches the IR golden;
- `semx emit-arm32-proof tests/smoke.snx` writes the fixed artifact;
- artifact bytes begin `7f 45 4c 46` and report `EM_ARM`.
