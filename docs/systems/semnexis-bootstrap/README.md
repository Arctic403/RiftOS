# Semnexis QuickJS Bootstrap

## Status

**0.3 DEVICE VERIFIED / 0.4 RUNTIME ARM32 SOURCE VERIFIED, DEVICE PROOF PENDING — 2026-09-19**

Semnexis is bootstrapped by a bounded JavaScript compiler hosted inside RiftOS headless QuickJS. Clang, GCC, CMake, LLD and raw subprocess execution are not part of the active bootstrap.

Semnexis 0.3 was device-verified on RiftOS source `8a01a0aad794d9721f345b9ab7c26e27f183b527`. That installed-device proof covered Program Graph generation, Execution Plan generation, Native IR V0, `SNIRV0` binary encode/decode/reverify and the fixed ARM32 constant-proof ELF artifact.

Current source is `0.4.0-quickjs-bootstrap`. It adds a runtime-valued ARM32 backend that no longer evaluates supported add/sub/call programs at compile time.

Current source pipeline:

```text
.snx source
    -> Program Graph
    -> graph verification
    -> effect/capability solving
    -> Execution Plan
    -> SEMNEXIS_NATIVE_IR_V0
    -> IR verification
    -> optional SNIRV0 serialization/reload/reverify
    -> ARM32 backend
       -> constant proof backend (reference)
       -> runtime-valued backend (current path)
    -> ELF32 / EM_ARM artifact
```

QuickJS remains only the bootstrap host.

## Bootstrap surface

RiftShell exposes fixed commands:

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

The two binary commands may write only:
- `/documents/builds/Semnexis/semx-arm32-proof.elf`
- `/documents/builds/Semnexis/semx-arm32-runtime.elf`

Generated writable RiftFS artifacts are not executed by this path.

## Semantic and IR contract

The compiler owns:
- V0 parsing and name resolution;
- deterministic Program Graph;
- graph verifier;
- effect/capability solving;
- Execution Plan;
- `SEMNEXIS_NATIVE_IR_V0`;
- typed SSA values;
- explicit regions/effects/capabilities;
- Program Graph provenance;
- use-before-definition and call-shape verification;
- checked signed-`i32` arithmetic semantics;
- `SNIRV0` canonical binary serialization;
- source-independent decode + IR re-verification.

Backends must preserve Semnexis arithmetic semantics rather than inheriting target undefined behavior.

## ARM32 constant proof backend

`SEMNEXIS_ARM32_ELF_PROOF_V0` remains as a transition/differential backend. It evaluates supported pure V0 IR at compile time and emits a deterministic 100-byte ARM32 ELF containing the result.

This backend is device-verified and is no longer the primary backend direction.

## ARM32 runtime backend

`SEMNEXIS_ARM32_RUNTIME_ELF_V0` is the first runtime-valued backend.

Current source-verified lowering:
- deterministic 32-bit stack slot per SSA value;
- 8-byte-aligned function frames;
- up to four `i32` parameters through `r0-r3`;
- call arguments through `r0-r3`;
- real ARM `BL` calls;
- return values in `r0`;
- constants through `MOVW/MOVT`;
- runtime copies through stack loads/stores;
- checked add through `ADDS` + `BVS`;
- checked subtract through `SUBS` + `BVS`;
- shared overflow trap exiting with code 125;
- nested calls;
- deterministic ELF32/EM_ARM layout.

The runtime fixture `add(40, 2)` emits a 268-byte image with:
- two runtime functions;
- a real `BL` from `main` to `add`;
- runtime `ADDS` in `add`;
- `BVS` resolving to the shared trap;
- `constantEvaluated=false`;
- `runtimeLowered=true`.

Current V0 runtime-backend limits are fail-closed:
- multiply not yet lowered;
- divide not yet lowered;
- effects/capabilities not yet lowered;
- recursion/call cycles rejected;
- more than four parameters rejected;
- no branches/loops yet because the language does not expose them.

## Source ownership

Maintained owners:
- `src/semnexis-bootstrap.js` — compiler, Program Graph, Native IR, `SNIRV0`, constant ARM32 proof backend and runtime ARM32 backend;
- `RiftHeadlessJsRuntime.kt` — bounded QuickJS host, source reads and exact-path binary artifact writer;
- `RiftNativeShell.kt` — fixed `semx` routing;
- `android/app/build.gradle.kts` — packaged bootstrap asset;
- `scripts/test-semnexis-bootstrap.mjs` — compiler/IR/backend regression suite;
- `scripts/test-semnexis-shell.mjs` — host/shell/authority boundary suite.

The language-project mirror is `workspace/Semnexis/bootstrap/quickjs/semnexis-bootstrap.js` and must remain byte-identical during bootstrap.

## Failure signatures

Invalid states include:
- WebView/generic-eval routing for `semx`;
- source path escape;
- process/shell/network authority;
- binary output outside the two fixed Semnexis paths;
- return of `riftclang`/native Clang bootstrap authority;
- non-deterministic graph/IR output;
- malformed IR passing verification;
- malformed `SNIRV0` passing decode/reverify;
- backend arithmetic violating checked-`i32` rules;
- runtime backend silently accepting unsupported multiply/divide/effects;
- incorrect `BL` or overflow-branch relocation;
- unaligned runtime frames;
- generated writable artifacts being executed.

## Fix map

- frontend/Program Graph/IR/backend -> `src/semnexis-bootstrap.js`
- QuickJS host + fixed artifact writes -> `RiftHeadlessJsRuntime.kt`
- shell surface -> `RiftNativeShell.kt`
- source/backend regressions -> `scripts/test-semnexis-bootstrap.mjs`
- shell/authority regressions -> `scripts/test-semnexis-shell.mjs`
- language roadmap/status -> `workspace/Semnexis`

## Validation

Current source proof requires:
- Program Graph/Execution Plan/IR goldens;
- deterministic `SNIRV0`;
- decode/reverify;
- constant proof backend verification;
- runtime backend ELF verification;
- runtime `ADDS/SUBS`;
- overflow `BVS` resolves to trap;
- `BL` resolves to actual target function;
- nested calls lower to multiple `BL` instructions;
- frames are 8-byte aligned;
- multiply/effects fail closed;
- RiftOS/Semnexis compiler mirrors hash identically;
- audits add no new findings.

Next APK device gate:
- `semx self-test` reports `0.4.0-quickjs-bootstrap`;
- self-test reports `SEMNEXIS_ARM32_RUNTIME_ELF_V0`;
- runtime image reports 268 bytes / two functions;
- `arm32RuntimeLowered=true`;
- `arm32RuntimeConstantEvaluated=false`;
- `arm32RuntimeHasCall=true`;
- `arm32RuntimeHasCheckedAdd=true`;
- `semx emit-arm32-runtime tests/runtime_calls.snx` writes the fixed runtime artifact.
