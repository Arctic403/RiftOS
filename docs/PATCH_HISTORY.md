# RiftOS Patch History

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-21.**

This file records source-first implementation patches. It is not authority by itself: source code, Gradle packaging, manifest state, focused tests and direct audits outrank this history. Each entry describes what changed, where, why, how it works, what it affects, validation performed, limits/risks and rollback scope.

## Patch 10.27 — N1.7 deterministic SSE lifecycle

### Live stress finding

External browser stress on 2026-09-21 proved the relay's separate eight-client SSE ceiling and fail-closed backpressure path, but also proved backpressure cannot be the primary stale-client detector. Eight browser streams were accepted, the ninth was correctly rejected, and closing all visible tabs left all eight Durable Object entries registered. Event pressure evicted six through `backpressureDropped`, while two idle streams remained because the outer Worker/Chrome path continued to appear writable.

The failure had three concrete causes. Direct browser SSE requests carried no stable `Mcp-Session-Id`, so same-session replacement and explicit close could not identify them. The Worker wrapped the Durable Object response in a second `ReadableStream`, introducing another buffering boundary between the room's `desiredSize` and the real browser connection. The Cloudflare Worker configuration also did not explicitly enable incoming `Request.signal` cancellation, and there was no absolute lease to guarantee eventual cleanup if abort and backpressure both failed.

### Source repair

Production SSE now requires a stable `Mcp-Session-Id`. Manual browser diagnostics may instead supply a validated `?subscriber=<id>` value limited to 128 characters from `[A-Za-z0-9._:-]`; the Worker prefixes it internally as `diag:<id>` so diagnostic identities cannot silently become anonymous streams. Anonymous SSE opens fail closed and increment `anonymousRejected`.

The outer Worker now returns the Durable Object SSE response directly instead of copying it through `proxySseResponse`. `relay/wrangler.jsonc` explicitly enables `enable_request_signal` and `request_signal_passthrough`, allowing client cancellation to propagate to the request signal used by the room.

Every SSE client also receives an absolute 180-second lease plus up to 30 seconds of jitter. Lease expiry removes the client, clears heartbeat/lease timers and abort listeners, closes the stream, increments `leaseExpired`, and relies on `Last-Event-ID` for cursor-safe reconnect. The existing byte backpressure and two-heartbeat no-drain checks remain secondary memory/liveness protection.

No Durable Object storage, event payload persistence or offline queue was added.

### Validation and status

`test-rift-cli-push-channel.mjs` and `validate-rift-transport.mjs` now require the stable identity, lease cleanup, direct-streaming contract and Cloudflare request-signal flags. This patch is source-complete pending Builder validation and live Worker deployment. N1.7 remains unpromoted until external browser re-test proves closed streams return `sseClients` to zero without event pressure and the subsequent Android force-stop/reopen test proves restart recovery from the persisted ACK cursor.

## Patch 10.26 — N1.7 force-stop cursor durability and stale-SSE cleanup

### Live stress finding

Installed-device stress on 2026-09-21 against source `a2b29555445fdcc7ea2ad228f98fd8a1af5a6833` proved the first relay cursor-recovery repair live. Manual relay replacement resumed from the exact current ACK high-water instead of zero, replay count stayed zero, five consecutive replacements recovered on the next MCP call, five 16-wide MCP waves completed 80/80, and device replacement did not strand seven simultaneous scans. External SSE cap testing opened eight browser streams and correctly rejected the ninth with `Too many MCP SSE subscribers`.

Closing all eight browser tabs exposed a separate lifecycle leak: after multiple heartbeat windows, the Durable Object still reported eight SSE clients because Chrome/Cloudflare had not propagated abort/cancel. Deliberate ~37 KiB CLI event pressure then evicted six stale streams through `backpressureDropped`, proving the 512 KiB fail-closed queue path works live, while two idle streams remained registered because they continued to appear drainable from the Worker side.

A pre-force-stop audit also found that Android `lastCliAckSequence` was RAM-only. Ordinary relay reconnect was fixed, but a true Android process death could still reset `device.hello.cliAckSequence` to zero if the Durable Object also lost its in-memory cursor while the phone was offline.

### Source repair

`RiftRelaySettings` now stores the highest relay-ACKed CLI sequence as a monotonic app-private `Long`. `RiftMcpRelayClient` restores that value at construction and synchronously persists each newly advanced ACK before a later force-stop can erase the process copy. The persisted value contains no token, payload or event body.

The Worker now records SSE queue `desiredSize` progress across heartbeats. A client with queued bytes and no forward drain progress across two consecutive heartbeat observations is evicted through the existing backpressure cleanup path. Existing byte-cap protection remains unchanged, so either a full queue or a no-drain stream fails closed instead of living indefinitely.

No Durable Object storage or event-payload persistence was added. Focused and broad transport validators now require the persisted Android ACK cursor plus the no-drain heartbeat contract.

### Validation and status

These changes are source-complete pending Builder validation, APK installation and Worker deployment. N1.7 remains unpromoted until a new live pass proves: force-stop/reopen restores the persisted ACK cursor without a zero replay, closed browser SSE tabs age out automatically without event pressure, and subscriber count returns to zero. Existing live proof already covers the eight-client SSE ceiling, ninth-client rejection, real backpressure eviction, repeated relay replacement, concurrency and transport size bounds.

## Patch 10.25 — N1.7 relay cursor recovery hardening

### Live stress finding

Installed-device relay torture testing on 2026-09-21 against source `6d21cd5fd2d9ef2331c8ec42a8654d7de07dd31f` exercised concurrent MCP bursts, repeated relay replacement, CLI disable/re-enable, health-counter cleanup and device-to-relay CLI event ACKs. Normal reconnects recovered on the next MCP call and preserved the current resume cursor. One `EOFException` recovery path exposed a real transport defect: after the device WebSocket attachment and room in-memory cursor were no longer available, the next `relay.ready` returned `cliResumeAfter=0`. The still-running Android process retained its device-owned event ring and replayed 29 already-ACKed events. Cloudflare ACKed those duplicates as non-advancing, so CLI authority did not re-execute, but the reconnect produced unnecessary replay traffic and proved that the relay high-water mark was not fully recoverable from a dead device socket alone.

### Source repair

`RiftMcpRelayClient` now includes its process-local `lastCliAckSequence` in authenticated `device.hello` as `cliAckSequence`. `RiftRelayRoom` validates that value as a non-negative safe integer, monotonically merges it into `lastCliSequence`, refreshes the current device WebSocket attachment, and only then calculates `relay.ready.cliResumeAfter`. Active subscriber cursors can still lower the requested replay point when older events are genuinely required.

The fix deliberately adds no Durable Object storage and persists no event payloads. `test-rift-cli-push-channel.mjs` and `validate-rift-transport.mjs` now require the device-owned ACK handshake so a future relay rewrite cannot silently regress to attachment-only cursor recovery.

### Validation and status

This patch is source-complete only until Builder validation, APK installation and Worker deployment. N1.7 remains unpromoted. The live re-test must reproduce EOF/socket replacement recovery and confirm that the reconnect resumes from the highest ACKed device cursor instead of zero. Slow-SSE/backpressure, SSE subscriber-cap saturation and a true Android process force-stop/restart remain separate installed-device proofs.

## Patch 10.24 — RiftBrowser bounded editor bridge

### Source change

Extended the existing active-page RiftBrowser inspector so RiftOS can edit browser-hosted code/text editors without adding arbitrary JavaScript execution or a second WebView owner. The new `edit` action accepts only non-sensitive text-like inputs, textareas and contenteditable surfaces, uses native value setters plus input/change events for framework-backed controls, records the original value/text for inspector reset, and rejects password plus password/secret/token/API-key/authorization-like controls.

RiftShell exposes `riftos-agent browser-inspect edit <selector> <text>` and `edit-b64 <selector> <base64-utf8>`. `edit-b64` preserves complete source text across shell parsing; the Android bridge decodes canonical UTF-8 and enforces a 256 KiB payload ceiling. No submit/deploy click authority, cookies, storage, headers, innerHTML, control-value readback or arbitrary page script execution was added.

### Validation and status

`validate-rift-wiring.mjs` now requires the bounded editor bridge, Base64 transport, secret-field guard and shell commands so this capability cannot silently disappear. Browser and Local Agent subsystem docs describe the new contract. This patch is source-complete only until Builder validation and installation of the resulting APK; after install, the intended acceptance test is to inspect an active HTTPS code editor, edit a disposable/non-secret field or source buffer, reset it, then use the same bridge against the Cloudflare Worker editor before any explicit deploy click.

## Patch 10.23 — N1.7 zero-poll steady-state contract lock

### Source hardening

Promoted push-first observation from a documented preference into an explicit native RiftCLI contract without removing recovery controls. The C++ status/architecture surfaces now advertise `driverObservationMode=persistent-push-steady-state`, `automaticPolling=false` and `pollFallbackOnly=true` alongside the existing persistent relay push/replay fields.

No automatic polling loop existed in the runtime before this patch: `rift_cli_job_poll` was already invoked only through the explicit external job-control route. This patch locks that structure with regression coverage so a future internal poll loop cannot be introduced silently. `rift_cli_job_list` and `rift_cli_job_poll` remain explicit recovery/debug fallbacks, while `rift_cli_job_cancel` remains an explicit control surface.

### Validation and status

`test-rift-cli-driver-protocol.mjs` now requires the zero-poll contract fields and verifies the bounded poll call-site structure: one shell poll helper definition plus its explicit control call, one ToolHost poll helper definition, and one explicit ToolHost poll call. `validate-rift-wiring.mjs` also requires the new native contract markers.

RiftCLI remained OFF while this source hardening was made. The change is source-complete only until the external Builder runs the Node/Android validation chain and an updated APK is installed. N1.7 is not promoted by this patch; forced restart, repeated reconnect, backpressure/subscriber caps, large-result fallback, cancellation/no-interleave, concurrent-driver pressure and broader bounds remain pending.

## Patch 10.22 — N1.5 persistent SSE push/replay live promotion

### Installed-device proof

Promoted RiftCLI N1.5 on the installed Android build from source `0814fb8cf8ca31186d6e639fc7ad3b965d822897` after completing the missing external-subscriber proof against the live `rift-mcp-relay` Cloudflare Worker/Durable Object.

The live `/health` surface reported `deviceConnected`, `driverSockets`, `sseClients` and `cliSequence`. With an external Chrome SSE subscriber attached (`sseClients: 1`), a read-only RiftCLI `version` job emitted `job.submitted`, `job.started` and `job.completed`. RiftDebugHub independently recorded matching `riftcli.event-bus/event.created`, `mcp.relay/cli.event.send`=`queued`, and `mcp.relay/cli.ack`=`received` records for the same event sequences. The Chrome subscriber received those exact sequences and the inline terminal result without job polling.

### Reconnect/replay proof

The SSE client was fully disconnected until `/health` reported `sseClients: 0`. A second read-only `version` job then created a three-event gap while no SSE subscriber existed. Reconnecting with the previous `after` cursor produced the `notifications/riftcli/ready` event followed by exactly the three missed lifecycle sequences, in order, with no replay of the cursor event and no older duplicate.

### Promotion result

N1.5 persistent push/events is therefore live-proven for basic external SSE delivery, device-to-relay ACK correlation, cursor reconnect and duplicate-free missed-event replay. Poll/list/cancel remain recovery/debug fallbacks. Large-result fallback, forced relay/device restart abuse, repeated reconnect pressure, slow-subscriber/backpressure behavior, subscriber caps, cancellation/no-interleave and broader bounds remain N1.7 stress work. RiftCLI was returned to its default OFF state after proof.

This promotion updates documentation/status only; it does not change runtime authority, relay secrets, Durable Object bindings or CLI enable defaults.

## Patch 10.21 — N1.5 Builder validation closure

### Failure reproduced

Public Builder run `35542173887` for RiftOS source `c9e7852661840aaaee90a760d2737269455347eb` failed before product validation because `scripts/validate-rift-wiring.mjs` contained literal `\\n` characters inside the N1.5 runtime-wiring condition. Node rejected the validator itself with a syntax error.

### Source repair

Repaired the malformed condition and extended `validate-rift-wiring.mjs` so N1.5 wiring now also requires the passive DebugHub component/hooks:

- `riftcli.event-bus` + `event.created`;
- `mcp.relay`;
- `cli.event.send`;
- `relay.ready`;
- `cli.replay.request`;
- `cli.replay.send`;
- `cli.ack`.

The focused `test-rift-cli-push-channel.mjs` and `test-rift-debug-hub.mjs` remain part of `npm run check`.

### External Builder hardening

The public Builder now independently `node --check`s the critical source-gate entrypoints before running the source-owned validator. It also fails if `package.json` no longer routes wiring, transport, docs, RiftCLI push, Batch V2 or DebugHub checks through `npm run check`.

The final signed-APK verifier now requires DEX to contain the N1.5 diagnostic component/operation markers in addition to the mandatory Kotlin class descriptors. This proves the specific event/relay instrumentation survived compilation rather than only proving its owner classes exist.

No installed-device behavior is claimed by these source/Builder checks. N1.5 still requires the green artifact, install, DebugHub device-to-relay ACK proof and final external subscriber push proof.

## Patch 10.20 — RiftCLI N2 federated memory roadmap freeze

### Roadmap/documentation changes only

Froze the full pre-N3 RiftCLI N2 memory program in `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md` without claiming any new runtime capability.

The frozen architecture requires one canonical Rift Memory Kernel and one canonical evidence/event/transaction/reconciliation authority. Specialized temporal/graph, episodic, consolidation, semantic, belief/reflection, skill/procedural, failure, causal, commitment and predictive engines operate as rebuildable cognitive views over canonical IDs rather than independent sources of truth.

N2 starts with a replaceable SQLite reference `MemoryStore`. RiftStore is an experimental backend that may replace SQLite responsibilities only when identical benchmark workloads show enough correctness/resource/performance benefit to justify the added complexity. Logical JSON-shaped schemas remain independent from physical storage encoding.

The roadmap freezes evidence-vs-belief separation, bi-temporal history, protected policy/authority, governed memory transactions, Observer/Validator reconciliation, Difference/Surprise handling, NO/FAST/DEEP/FORENSIC retrieval modes, multi-index fusion, Context Compiler, speculative branch isolation, fsck/snapshot/replay/rollback, poisoning defenses, crash consistency, scale gates, public/private benchmark suites, specialist metrics, incremental hybrid benchmarks and ablations.

Implementation is split into N2.0 through N2.12. N3 is explicitly blocked until N2.12 promotion proves the mandatory weakest-link categories and bounded Android resource behavior. Happy-path demos or strong average benchmark scores cannot waive a failing critical category.

Updated `ROADMAP.md`, RiftCLI docs, RiftMemory classification, project status and docs index to point at the frozen N2 program while preserving `src/riftmemory-control.js` as inactive retained reference source. Extended `scripts/validate-rift-docs.mjs` so Builder/documentation validation fails if the N2 roadmap, SQLite/RiftStore storage split or hard N3 barrier disappears.

## Patch 10.19 — N1.5 passive relay/event observability

### Current source changes

Connected the existing process-wide RiftDebugHub to the RiftCLI persistent-push path without placing the debugger in the execution or authority path.

`RiftCliEventBus` now emits bounded `event.created` metadata through component `riftcli.event-bus`. `RiftMcpRelayClient` now emits bounded metadata through component `mcp.relay` for socket connect/open/close/failure/reconnect, `relay.ready`, CLI event queue attempts, replay request/send and `cli.ack` receipt.

The trace deliberately separates three evidence boundaries: local event creation, local OkHttp WebSocket queue acceptance, and Cloudflare relay acknowledgement. A matching `cli.ack` proves the relay received that event sequence; it does not by itself prove an external SSE/WebSocket subscriber consumed the event.

Diagnostics are metadata-only. They do not include MCP payload bodies, CLI result bodies, relay endpoint URLs, Authorization headers or pairing tokens. DebugHub remains passive/read-only and owns no network, execution, mutation or cancellation authority. Event-bus and relay diagnostic emission is wrapped in fail-isolation so an unexpected debugger exception cannot block CLI event delivery, socket handling or replay.

Updated the N1.5 push/debug regression locks, debugger/relay/RiftCLI documentation, ownership ledger, roadmap and project status. N1.6 Batch V2 status is corrected to live-proven on Builder run #259 / source `eaa2a390438784be435929e49283f9e6281b8ed0`; N1.5 still requires the final external push-receipt proof after this instrumentation is built and installed.

## Patch 10.18 — Retired RiftShell batch regression narrowed for Batch V2

### Current source changes

Builder run `35537848556` on source `508f6fbe175d3af34282ceb047af00e23b0d4e62` passed RiftCLI N1.5 persistent push, RiftCLI N1.6 Batch V2, transport, wiring and documentation validation, then failed in the legacy retired-batch regression.

The old test rejected any occurrence of the literal `"batch"` inside `RiftNativeShell.kt`. That became stale once the new, separately-authorized `rift_cli_batch` Batch V2 path was added. The regression now rejects the actual retired native RiftShell command dispatch pattern (`"batch" ->`) instead.

The old RiftShell batch command remains disabled. This patch changes the test only; Batch V2 runtime behavior and authority are unchanged.

## Patch 10.17 — RiftCLI N1.5/N1.6 source ownership ledger repair

### Current source changes

Builder run `35537600791` on source `f31f9505e3e3edc8302caa516626484c2678bc27` passed native wiring, the 19-tool MCP surface, relay/transport validation, RiftCLI persistent-push validation and RiftCLI Batch V2 validation. It then stopped in documentation validation because three newly maintained N1.5/N1.6 sources had no documentation ownership rows.

Added exact ownership entries for:
- `android/app/src/main/java/com/riftos/app/RiftCliEventBus.kt`;
- `scripts/test-rift-cli-push-channel.mjs`;
- `scripts/test-rift-cli-batch-v2.mjs`.

The entries point to the existing RiftCLI, relay and build-validation documentation that already describes those sources. Runtime behavior, MCP configuration, relay identity and authority boundaries are unchanged.

## Patch 10.16 — RiftCLI pre-N2 Builder validator diagnostics hardening

### Current source changes

Builder run `35537136265` on source `5f6e951760194cff41a93836b72841ba34a4542d` stopped in `validate-rift-wiring.mjs` at the combined RiftCLI N1/N1.5/N1.6 core-contract assertion. Direct inspection of the pushed source proved every individual fragment in that combined assertion was present.

The validator now checks the same contract as individually named requirements instead of collapsing roughly twenty independent conditions into one generic “boundary drifted” failure. Replay protection still separately fails if `g_recentRequestIds.clear()` returns.

This changes validation diagnostics only; RiftCLI authority, relay configuration, MCP endpoint/tool surface, Batch V2 behavior and Android runtime code are unchanged by this patch.

## Patch 10.15 — RiftDebugHub passive global debugger foundation

### Current source changes

Added the first process-wide global debugger layer without placing it in the execution path:

- `RiftDebugHub.kt` owns bounded in-memory spans/events, monotonic durations, trace correlation, active-span visibility, capacity counters and secret-key redaction;
- `RiftDebugAdapter` + `RiftDebugSink` provide the reusable subsystem plug;
- `RiftMcpRuntime` owns one hub per Android process;
- `RiftMcpServer` creates the parent tool-call span, forwards its context and returns `riftos/traceId`;
- `RiftToolHost` creates the child span and exposes one read-only `rift_debug` tool with status/events/active/components actions;
- the MCP catalog is now 19 tools;
- the exact Gradle Kotlin source snapshot is now 46 files;
- focused regression, transport validator, ownership ledger and subsystem documentation were updated together.

Authority remains unchanged: the hub cannot execute, mutate, cancel, read files, access the network, host a model, grant permission or enable RiftCLI. RiftShell batch remains disabled. Current proof is source/static-validation level; APK compilation and installed-device behavior remain the next build gate.

## Patch 10.14 — Semnexis SNIRV7 Arena AST + bounded recursion pressure loop

### Current source changes

Continued the Semnexis 0.7 self-hosting pressure loop from parser-state records into native AST storage and recursive parsing.

Added and verified:
- a first-class borrowed `Arena` state descriptor with fixed 16-byte flat-record cells;
- typed `arena_store(arena,index,record)` and `arena_load<Record>(arena,index)` with descriptor/index/capacity/data-pointer validation;
- derived `state` effects for Arena reads/writes and additive `SNIRV7`, while SNIRV0–SNIRV6 remain frozen compatibility surfaces and reject newer semantics;
- ARM32 Arena load/store lowering plus canonical machine-image verification and independent machine execution over seeded memory;
- flattened record parameter ABI beyond r0-r3 using aligned caller stack words, including parser-state records;
- bounded direct and mutual native recursion. Only functions participating in recursive call cycles receive the backend-private `r11` depth guard; frame 256 succeeds and frame 257 traps through the canonical runtime trap;
- canonical verifier checks for the actual recursion-guard instruction sequence and trap branch target, not metadata alone;
- recursive-descent parsing of `1+(2+3)` into a five-node Arena-backed AST followed by recursive AST evaluation to `6`;
- permanent Semnexis self-host probe `parser_recursive_arena_probe.snx` plus its QuickJS runner;
- embedded source `semx self-test` promoted to `semnexis-bootstrap-self-test/17`, reporting SNIRV7 Arena-read, parser-state stack ABI, record-loop-yield and bounded-recursion proof metrics;
- RiftOS/Builder documentation parity updated for the third packaged headless asset `src/semnexis-bootstrap.js`.

The installed APK still exposes older `semx` wiring until the next RiftOS build/install. Current 0.7 SNIRV7/Arena/recursion work is source + independent-machine-regression verified; APK/device promotion remains a separate gate.

## Patch 10.13 — Semnexis 0.7 record/parser pressure loop

### Current source changes

Continued the self-hosting pressure loop by compiling increasingly real lexer/parser kernels and adding only the general capabilities those kernels exposed.

Added and verified:
- flat immutable records (up to four scalar fields), `SNIRV3`, deterministic aggregate stack slots and r0-r3 record returns/calls;
- record field projection through `record.get` and frozen additive `SNIRV4`;
- record-valued conditionals and explicit-state record loop values through `phi.record`, additive `SNIRV5`, and the existing cycle-safe parallel phi edge-copy resolver;
- generic CFG return handling for `ret.i32`, `ret.u8` and `ret.record`;
- post-definition phi type verification, including backedge-safe record phi validation;
- variable-width numeric token spans over borrowed `Slice<u8>` source;
- a native streaming parser-state kernel that accepts valid `digit + digit` forms and rejects incomplete/extra/wrong-operator forms;
- verified zero-extension from semantic `u8` to `i32` through `zext.u8.i32` and additive `SNIRV6`;
- native decimal accumulation (`1234` -> integer `1234`) using checked arithmetic;
- independent ARM32 execution regressions for record returns/calls/projection, record branch/loop state, variable-width token spans, parser state and numeric widening;
- embedded `semx self-test/13` proof for the V6 binary/runtime path;
- Builder wiring guards for SNIRV6, zext and `/13` host proof.

Compatibility remains additive and fail-closed: V0-V5 remain explicit encoders/decoders, and older formats reject newer semantics rather than silently reinterpreting them.

0.6 remains installed-device verified. The 0.7 lexer/parser/V6 work is source + independent-machine-regression verified and awaits the `/13` APK/device promotion gate.

## Patch 10.12 — Semnexis 0.7 self-hosting byte/slice pressure loop

### Current source changes

Semnexis advanced to `0.7.0-quickjs-bootstrap` by feeding real lexer requirements back into the language instead of predesigning unrelated features.

Added:
- real `u8` parameters/returns/literals with zero-extended 32-bit register representation;
- frozen `SNIRV0` preservation plus additive `SNIRV1` for `u8` IR;
- bounded generic type-reference parsing (`Name<T,...>`, maximum nesting 16);
- read-only borrowed `Slice<u8>` values with a fixed descriptor-pointer ABI;
- pure `slice_len` and bounds-checked `slice_get` intrinsics;
- additive `SNIRV2` for slice values/ops while V0/V1 reject newer value kinds;
- ARM32 word/byte loads and fail-closed descriptor/index/length/data-pointer checks;
- independent ARM32 execution over seeded external descriptor/data memory;
- a real native scanner kernel that walks `a1b23!` through `Slice<u8>` and returns digit count `3`;
- exact embedded `semx self-test/8` proof for SNIRV2 + canonical slice ARM32 lowering;
- Builder wiring guards for SNIRV2, slice IR/backend markers and `/8` host proof.

Compatibility remains explicit: i32-only IR auto-encodes as `SNIRV0`, `u8` IR as `SNIRV1`, and borrowed-slice IR as `SNIRV2`. Existing 0.6 smoke/conditional/loop binary sizes remain frozen.

0.6 remains device-verified on `1d743b6fda2f5e7f1085186bc4bf6e9bbbd3ef36`. The 0.7 self-hosting slice is source + independent-machine-regression verified and requires the next APK/device `/8` gate.

## Patch 10.11 — Semnexis 0.6.1 hardening gate

### Current source changes

Semnexis advanced to `0.6.1-quickjs-bootstrap` without adding language feature surface.

Hardening includes:
- effect/capability re-derivation from IR instructions and call graph;
- canonical IR-bound ARM32 machine verification;
- independent ARM32 machine-execution regression in `npm run check`;
- parallel-copy resolution for cyclic phi edges;
- bounded source/token/AST/graph/CFG/artifact/output resources;
- iterative call-cycle analysis;
- lazy bounded graph/plan/IR dumps;
- frozen `SNIRV0` V0 version/flags/opcode compatibility tests;
- advisory-only graph-node correlation semantics made explicit;
- exact embedded `semx self-test/7` execution in Builder;
- Semnexis-specific host source limit and pre-allocation binary payload checks.

0.6 remains device-verified on `1d743b6fda2f5e7f1085186bc4bf6e9bbbd3ef36`. 0.6.1 requires a new APK/device gate.

## Patch 10.10 — Explicit CFG, phi merges and explicit-state loops

### Current source changes

Semnexis advanced to `0.6.0-quickjs-bootstrap`.

Added:
- signed comparison syntax `== != < <= > >=`;
- expression-oriented `if ... { ... } else { ... }`;
- internal control-flow `bool` facts;
- explicit Native IR basic blocks and branch terminators;
- `phi.i32` merge semantics;
- CFG reachability/predecessor/dominator verification;
- `SNIRV0` serialization for blocks, branches and phi incoming edges;
- ARM signed conditional branches and named block relocation;
- conservative `cfg-spill-v0` for control-flow functions while straight-line functions retain the proven `linear-scan-r4-r7-v0` allocator.

Added explicit-state loops:

`loop (state = initial, ...) while condition { next (nextState, ...); } yield value`

Loop state is represented semantically, not as hidden mutable locals. Header phis receive preheader and backedge inputs; all next-state values are simultaneous.

### Source proof

- all 0.5 straight-line graph/plan/IR/arithmetic sizes remain unchanged;
- six signed comparison predicates verified against named ARM targets;
- conditional: 674-byte `SNIRV0`, 340-byte ELF, 4 blocks;
- nested conditional: 7 blocks / 444-byte ELF;
- loop: 756-byte `SNIRV0`, 368-byte ELF, 4 blocks;
- loop preheader and backedge each perform two phi edge copies;
- loop emits a real backward ARM branch to its header;
- invalid conditions, chained comparisons, malformed next-state arity, state shadowing and duplicate loop-state names are rejected.

Device proof requires the next APK.

## Patch 10.9 — Full checked i32 ARM32 arithmetic + liveness allocation

### Current source changes

Semnexis advanced to `0.5.0-quickjs-bootstrap`.

The ARM32 runtime backend now includes:
- linear-scan live-range allocation into callee-saved `r4-r7`;
- deterministic stack spills only when live-range pressure exceeds four value registers;
- checked runtime multiply using `SMULL` plus high/sign-extension verification;
- checked runtime divide using a shared software divider instead of optional ARM `SDIV`;
- divide-by-zero and `INT32_MIN / -1` overflow trapping;
- complete checked runtime `i32` add/sub/mul/div coverage.

The register-allocated add/call fixture shrank from 268 bytes to 192 bytes with zero spill frame. A forced-pressure fixture proves deterministic spilling. The full arithmetic fixture emits a 1016-byte ELF with a 716-byte shared divider and one spill slot.

The software divide algorithm matched signed-`i32` reference semantics across 417 deterministic boundary/stress cases. The full ELF was independently disassembled as ARMv7 and confirmed the intended arithmetic, call and branch instructions.

Device proof requires the next RiftOS APK.

## Patch 10.8 — Runtime-valued ARM32 lowering

### Current source changes

Semnexis bootstrap compiler advanced to `0.4.0-quickjs-bootstrap`.

Added `SEMNEXIS_ARM32_RUNTIME_ELF_V0`, a direct runtime-valued ARM32 backend that consumes verified Native IR without whole-program constant evaluation.

Current lowering:
- deterministic stack slot per SSA value;
- 8-byte-aligned frames;
- `r0-r3` parameter and call-argument ABI;
- `BL` function calls;
- `r0` returns;
- `MOVW/MOVT` constants;
- runtime copies;
- checked add/sub using `ADDS/SUBS` plus `BVS` to a shared overflow trap;
- nested calls;
- fail-closed rejection for multiply/divide/effects/recursion until those lowerings exist.

The runtime fixture emits a 268-byte ELF32/EM_ARM image with `constantEvaluated=false` and `runtimeLowered=true`.

Fixed runtime artifact output:
- `/documents/builds/Semnexis/semx-arm32-runtime.elf`

Generated writable artifacts remain non-executable by RiftOS policy. Device proof requires the next APK.

## Patch 10.7 — Semnexis Native IR V0 + direct ARM32 backend seed

### Current source changes

Extended the QuickJS-hosted Semnexis compiler to `0.3.0-quickjs-bootstrap`.

Added:
- typed SSA-like `SEMNEXIS_NATIVE_IR_V0`;
- deterministic IR verifier and textual dump;
- checked signed-i32 add/sub/mul/div semantics;
- canonical binary IR format `SNIRV0`;
- binary decode + source-independent re-verification;
- `semx dump-ir`;
- direct pure-program ARM32 backend proof;
- deterministic ELF32/EM_ARM image verifier;
- fixed `semx emit-arm32-proof` output at `/documents/builds/Semnexis/semx-arm32-proof.elf`.

The ARM32 proof backend is intentionally narrow: pure/capability-free, zero-input V0 only. It evaluates current V0 IR at build time and emits a real ARM EABI5 executable whose result is returned through the Linux/Android exit syscall. Runtime effects, inputs, recursion and unsupported operations fail closed.

Generated RiftFS ELF artifacts remain non-executable by policy.

### Current proof

Source-host execution proves:
- smoke IR: 1 function / 6 instructions;
- canonical `SNIRV0`: 157 bytes;
- multi-function IR binary: 411 bytes;
- effectful IR binary: 233 bytes;
- corrupt binary magic rejected;
- ARM32 ELF: 100 bytes, ELF32, EM_ARM, entry `0x10054`, result 42;
- effectful code rejected by the current backend.

Device proof for 0.3 requires the next RiftOS APK.

## Patch 10.6 — QuickJS Semnexis bootstrap replaces native Clang experiment

### Current source changes

The Semnexis bootstrap now runs through the already-packaged headless QuickJS runtime.

Active path:
- packaged \`src/semnexis-bootstrap.js\`;
- fixed native \`semx help|version|self-test|check|dump-graph|dump-plan\` command family;
- confined RiftFS source reads only;
- deterministic Program Graph + verifier + effect/capability solver + Execution Plan;
- V0 ambient capability grants restricted to the application boundary \`main\`;
- direct regression tests for graph/plan goldens and invalid programs.

Removed:
- \`RiftNativeToolchain.kt\`;
- the active \`riftclang\` shell route;
- Clang/LLD APK payload expectations;
- Builder Rift Clang workflow and payload scripts;
- native-toolchain subsystem documentation.

QuickJS is a bootstrap host only. It does not define the future Semnexis program runtime or native backend. The intended next compiler transition is Semnexis source compiling the Semnexis compiler itself.

### Validation gate

Source promotion requires the Semnexis bootstrap and shell-boundary regression tests, exact Android source inventory, documentation parity, full RiftOS audit/scans, then installed-device \`semx self-test\` and smoke-source proof.

## Patch 10.5 — Retired native Clang bootstrap experiment

Patch 10.5 briefly introduced a bounded Android-hosted Clang/LLD bootstrap host. It was retired before payload integration after the bootstrap strategy changed to the already-packaged bounded QuickJS runtime. No Clang/LLD payload is part of the active RiftOS Semnexis path.

## Patch 10.4 — Installed unsigned proof + bounded APK v2 sign/verify/install bootstrap

### Proven before this source patch

Installed RiftOS source `1c1ae33b81cfe643eb804cac0841ced636e982e3` / Builder run 214 executed the RiftBuild bootstrap path on-device:
- `prepare-riftpp-v0` materialized the AArch64 ELF at 1,064 bytes / SHA-256 `9cfc79cd6452d1c920c87b30a40a4c64561d216288b8bf5edc1fc480d07525ab`;
- it materialized the ARMv7 ELF at 732 bytes / SHA-256 `b4e91421b5078ad1b67f9a1f9f4e22a1bda08b8127255b7837e72e0f14007f49`;
- the fixed Android binary manifest matched 1,440 bytes / SHA-256 `ac035bb5bf89f55a3f34bae8eea980108324d2f36333f1e708f8a0b82af8e7c2`;
- project validate/plan reported the universal package ready;
- `riftbuild pack` produced a 1,843-byte unsigned APK with three entries and SHA-256 `a01c190f3b5475df1be59303ffc0a92623cff0dcde38a4743184dcd75e08f329`.

That is installed-device proof for the direct-ELF bridge, binary manifest and local unsigned APK packaging stages. It is not signing/install proof.

### Current source changes

Added `RiftApkV2Signer.kt`:
- one persistent AndroidKeyStore RSA-2048 signing key;
- APK Signature Scheme v2 algorithm `0x0103` (RSA PKCS#1 v1.5 + SHA-256);
- AOSP-style 1 MiB content-digest chunks and APK Signing Block insertion before the ZIP central directory;
- strict non-ZIP64/single-signer bootstrap parsing;
- independent certificate/public-key/signature/content-digest verification;
- sign operations self-verify before publishing their receipt.

Added `RiftBuildInstaller.kt`:
- `REQUEST_INSTALL_PACKAGES` / per-source Android trust handling;
- PackageInstaller session ownership with user action required;
- install restricted to exact package `com.riftpp.nativeproof`;
- persisted install result state;
- exact `android.app.NativeActivity` launch request;
- protected `PACKAGE_FIRST_LAUNCH` receipt recorded as `launch-proven`.

The existing `riftbuild` family now exposes only bounded `sign`, `verify`, `install-proof`, `install-status` and `launch-proof`; it adds no MCP tool, raw process/package-manager shell, automatic Git push or experimental CLI authority.

### Proof boundary

This signer/installer patch is **SOURCE IMPLEMENTED ONLY** until Android Builder compiles it and that APK is installed. The next proof sequence is exactly: sign the already-produced proof APK → independently verify v2 → Android PackageInstaller confirmation/install → launch → confirm first-launch status. After that bootstrap milestone, active development returns to RiftLLM+.

## Patch 10.3 — RiftBuild Kotlin regex escape repair

Builder run `35420418538` for source `bc8c0cb3a8012f2eef685354ab1253fea6baf1dd` passed source checks and Gradle validation, then reached real Kotlin compilation.

Kotlin compilation failed only in `RiftBuildLocalExecutor.kt:187`: the NativeActivity metadata regex used `\.` inside a normal Kotlin string. Kotlin interprets `\.` as an unsupported string escape before the regex engine sees it.

Repair:
- preserve the exact regex semantics;
- move the pattern to a Kotlin raw triple-quoted string so regex escapes remain regex syntax and require no Kotlin escaping.

No authority, behavior, MCP surface, CLI state or packaging contract changes in this repair.

## Patch 10.2 — Gradle Kotlin snapshot escape repair

Builder run `35420241649` for source `7d9de917ba5347c11f18bdde2ab4773aaf53d77b` passed the full RiftOS source/documentation gate and reached Gradle configuration.

Gradle then failed before Kotlin compilation because the exact mandatory Kotlin source list contained one literal `\\n` escape between `RiftBrowserWindow.kt` and `RiftBuildLocalExecutor.kt` instead of a physical newline. That single malformed token caused the subsequent parser-error cascade through the remainder of the list.

Repair:
- replaced the literal `\\n` with a real newline in `android/app/build.gradle.kts`;
- extended `validate-rift-wiring.mjs` to fail source validation if the mandatory Kotlin list ever contains this escaped-line-separator pattern again.

No Kotlin runtime source, MCP surface, CLI state or build authority changed in this repair.

## Patch 10.1 — Builder documentation-gate repair

Builder run `35419961262` for source `9052a0ffa913986142e32f79d3e12a8c32d61b32` stopped in `validate-rift-docs.mjs` before Android compilation.

The failure was documentation-only:
- RiftBuild README lacked the required exact `## Source ownership` maintenance section;
- its verification text did not match the validator's required source-verification marker;
- two SOURCE_OWNERSHIP table insertions contained literal `\\n` text, causing the RiftBuild executor and local-platform test ownership rows to be invisible to the row parser.

Repair:
- added the canonical `**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**` marker while preserving the explicit Android compile/device-pending boundary;
- added RiftBuild's exact source-ownership section;
- replaced both literal `\\n` table separators with real newlines.

No Kotlin, Gradle, MCP, CLI, build authority or runtime behavior changed in this repair.

## Patch 10 — Native RiftBuild bounded Android build core

### What changed

Promoted RiftBuild from an inactive JavaScript design plus non-executing app façade to a native, workspace-bounded Android build controller.

`RiftBuildLocalExecutor` now owns:
- source/project validation for workspace Android projects;
- deterministic project identity and bounded run records;
- fixed ARM32 / ARM64 / universal planning;
- the existing capability-gated `build.local` app methods;
- a native `riftbuild` shell command family;
- a real prepared-artifact APK ZIP stage under `D:/Builds`.

The prepared package stage only accepts compiled Android binary manifest input plus selected ABI `.so` payloads and optional bounded assets/resources. It writes an **unsigned** APK plus SHA-256 receipt and explicitly records `signed=false` and `installableClaimed=false`.

### Security boundary

This patch does not add `ProcessBuilder`, raw `exec`, downloaded toolchain execution, automatic Git push, experimental CLI enablement or any new MCP tool. Projects remain confined to `D:/Workspace`; outputs remain confined to `D:/Builds`.

Missing direct ELF emission, signing or PackageInstaller ownership produces a blocker rather than fake build success.

### Validation

`scripts/test-riftbuild-native.mjs` locks:
- workspace/output confinement;
- binary-manifest requirement;
- dual-ABI package expectations;
- unsigned/non-installable honesty;
- no process/CLI/MCP authority expansion;
- retained `src/riftbuild.js` remaining unpackaged reference source;
- native shell and existing `build.local` wiring.

The Android source snapshot is now exact 50/50 with `RiftBuildLocalExecutor.kt` included.

Actual Android compilation of this new Kotlin source and on-device RiftBuild execution remain Builder/install proof steps; source validation is not called APK/device proof.

### Next dependency

Implement direct Rift++ ARMv7/AArch64 ELF/shared-object emission into the prepared-artifact contract, then add bounded APK signing/verification and explicit PackageInstaller integration.

## Patch 9 — Impact-derived verification planner

### What changed

Added `RiftVerificationPlannerV1` and bound it into the manual OBSERVE-only CLI lifecycle.

The planner derives one exact `rift.verification-plan/1` from the final Patch Manifest / PI-v2 candidate and current repository state.

It owns:
- impacted test targets and bounded repository-check fallback;
- security targets from changed source/build config/direct dependents plus API/dependency-surface reasons;
- required repository `rift-audit` / `rift-scan` checks for source/build candidates;
- exact added/removed dependency delta reviews;
- changed build-config reviews;
- current dependency/build manifest reviews;
- deterministic check ids for every required verification action.

Security, dependencies and tests evidence now require:

`verificationPlan.schema = rift.verification-evidence/1`

with the exact `planSha256` and evidence kind from:

`rift-cli lifecycle verification-plan <sessionId>`

The generic evidence `checks` list must contain every planned check id with PASS status, and `targets` must contain every required plan target. Extra checks may be reported, but the required set cannot be replaced by a caller-selected subset.

Final evaluation recomputes the plan and binds `verificationPlanSha256` into the evaluator subject. Missing/incomplete/stale security/dependencies/tests plan evidence therefore denies the candidate.

### Fail-closed behavior

- source/build/test change with no impact test and no derivable repository validation fallback -> `NO_TEST_OR_VALIDATION_TARGET`;
- missing planned test -> hard plan issue;
- missing audit/scan/target/dependency/build-config check -> incomplete verification evidence;
- candidate mutation after verification -> `VERIFICATION_PLAN_STALE`.

Patch 9 remains a planner/evidence gate, not an autonomous test runner. It adds no MCP tool, process runner, dependency installer, trust promotion or publication authority.

### Regression

`scripts/test-rift-verification-planner-v1.mjs` locks schemas, bounds, test/security/dependency derivation, exact-check identity, lifecycle binding, Gradle/source ownership and zero MCP/trust expansion.

## Patch 8 — Documentation / project-state parity gate

### What changed

Added `RiftDocumentationParityV1` and bound it into the manual OBSERVE-only CLI lifecycle.

The new deterministic plan is derived from the exact Patch Manifest / PI-v2 candidate and checks:
- substantive owner documentation separately from bookkeeping (`PATCH_HISTORY` / `SOURCE_OWNERSHIP` cannot alone satisfy an owner-doc update);
- maintained source/build/test ownership;
- stale ownership after deletion;
- owner-document existence;
- mandatory owner-document updates for added/type-changed/API/dependency/build-config changes;
- ownership-ledger updates for added/deleted maintained files;
- PATCH_HISTORY updates for maintained source/build/test candidates;
- explicit review coverage for README, ROADMAP, PROJECT_STATUS, SOURCE_OWNERSHIP and PATCH_HISTORY when present.

Documentation evidence now requires a `documentationParity` object generated against:

`rift-cli lifecycle documentation-plan <sessionId>`

The normalized evidence must review every maintained changed path and every governance surface using the exact deterministic owner set. Final evaluation recomputes the plan and binds `documentationParityPlanSha256` into the evaluator subject; stale parity evidence therefore denies the candidate.

### What Patch 8 does not claim

The gate does not claim arbitrary English prose can be proven true by local Kotlin. `UNCHANGED_VALID` remains a bounded structured claim and the independent evaluator must re-check prose against exact source/impact evidence.

Patch 8 remains OBSERVE-only:
- no MCP tool;
- no trusted promotion;
- no publication;
- no autonomous documentation rewrite.

### Regression

`scripts/test-rift-documentation-parity-v1.mjs` locks the plan/evidence schemas, ownership rules, governance review rules, lifecycle binding, Gradle/source ownership and absence of MCP/trust expansion.

## RiftGit read-only history update

Added bounded native `git log` support to RiftGit before Patch 8. The command reads commit history through GitHub's commits API for the already attached repository and validated current branch; it does not invoke a local Git process or widen mutation authority.

Supported forms:
- `git log`
- `git log -n N`
- `git log -nN`
- `git log --max-count=N`
- optional `--oneline`

The default is 20 commits and the hard per-request cap is 100. RiftGit also now exposes `git head` and `git rev-parse HEAD`, while `git status` prints recorded HEAD explicitly. `git log` reports recorded local HEAD versus remote branch HEAD and whether they match. Results include structured SHA/message/author/committer/parent data. History reads do not write `.riftgit.json`, create Workspace Records checkpoints, change branch state or expose arbitrary remote/ref queries.

## Builder hotfix — lifecycle regression expectation after stress-foundation repair

Builder run `35405254326` for source `fdbb30c2e62f4d2c4b4c82540c64f8c107a6f1c3` passed source integrity and reached the focused lifecycle test, then stopped because `test-rift-cli-patch-lifecycle-v1.mjs` still asserted the pre-repair scope expression `understanding -> governance + buildManifests`.

The implementation was correct: the stress-foundation repair intentionally changed pre-patch UNDERSTAND/DESIGN to the acquired base inventory while post-patch evidence unions base + current inventory. The regression test now locks both sides of that contract instead of the obsolete expression. No runtime authority, lifecycle policy, MCP surface or trust behavior changed.

## Pre-Patch-8 Stress-foundation repair

### Why

Live abuse of RiftCLI Patch Lifecycle V1 exposed two real blockers before the documentation parity gate could be trusted:

1. a candidate that added new governance files could mark DOCUMENT_AUDIT complete while omitting newly introduced `TODO.md`, `docs/PATCH_HISTORY.md` and `docs/SOURCE_OWNERSHIP.md`;
2. process recreation could reset Experimental authority correctly but lifecycle-session durability was inconsistent, and an unexplained external workspace change/delete across restart could invalidate the candidate underneath evaluation.

### Repair

`RiftCliPatchLifecycleV1` now:

- derives post-patch governance scope from **base inventory + current inventory + Project Intelligence changed-documentation evidence**;
- derives dependency/security/build scope from **base build manifests + current build manifests + changed-build-config evidence**;
- keeps UNDERSTAND/DESIGN bound to the original base inventory;
- stores lifecycle sessions under `<filesDir>/riftfs/system/rift-cli-patch-lifecycle-v1`;
- migrates legacy sessions from the previous app-private root on access;
- records a process epoch plus last observed source snapshot/candidate manifest;
- if process epoch changes and source/candidate identity drifted, permanently records `restartDriftDetected`;
- blocks new evidence imports and evaluation for a restart-drifted session;
- surfaces the drift receipt in lifecycle status.

This does **not** claim the external deleter/root cause was identified. Source audit found no normal MainActivity/MCP-runtime/RiftGit-constructor path that intentionally deletes arbitrary untracked repository files on startup. The lifecycle therefore treats unexplained restart drift as unsafe instead of guessing intent.

### Regression

Added `scripts/test-rift-cli-stress-foundation.mjs` to the root check chain. It locks:

- current governance/build-manifest discovery;
- changed-documentation/build-config inclusion;
- base-only pre-patch scope;
- RiftFS system session storage + legacy migration;
- process-epoch/restart-drift detection;
- zero MCP/trust expansion.

### Authority

Still OBSERVE-only. No new MCP tool, trust promotion or publication authority.

## CLI Patch Lifecycle V1 — Patches 6/7 core + 8–10/12 foundation

### What changed

Added the manual OBSERVE-only CLI→AI→CLI patch lifecycle requested for real repository work:

```text
acquire full clean repo
→ understand complete layout/ownership
→ research external assumptions
→ document intent
→ patch through existing tools
→ audit documents
→ audit code
→ security/dependency/test/build verification
→ end-to-end/rollback verification
→ freeze exact candidate
→ send bounded evidence bundle to independent AI
→ locally verify returned hashes/verdict
```

### Research performed first

The design was checked against:
- SLSA v1.2 Source/Build guidance for immutable source revision, provenance and verification-summary concepts;
- in-toto attestation concepts for binding claims to exact subjects;
- NIST SSDF lifecycle secure-development practices.

RiftOS does not claim certification against those standards. The implementation adopts the useful patterns locally.

### Primary source

- `RiftCliPatchLifecycleV1.kt`
- `RiftResearchLedgerV1.kt`
- `RiftExperimentalCli.kt`

Existing evidence owners reused rather than duplicated:
- RiftGit;
- Project Export;
- Workspace Records/Patch Manifest;
- Project Intelligence V2;
- ToolHost internal candidate-impact seam.

### What was added beyond the original proposed workflow

- immutable Git HEAD + Project Export snapshot at acquisition;
- clean-tree requirement and optional native Git pull;
- bounded full-repository inventory;
- README/docs/ROADMAP/TODO/TASK/patch-history/status/source-ownership discovery;
- generated/vendor boundary discovery;
- dependency/build manifest discovery;
- source/version/claim research ledger;
- authoritative-source requirement for critical claims;
- pre-patch research→design ordering;
- actual candidate-derived audit targets;
- documentation→code→security/dependency→test/build→E2E/rollback ordering;
- lockfile/SBOM/license/provenance disposition;
- build environment + artifact SHA-256 evidence;
- explicit rollback evidence;
- stale-evidence invalidation against candidate manifest;
- final candidate/semantic/evidence/policy hashes;
- independent evaluator/patch-actor identity separation;
- bounded structured defects;
- evaluation packet size limit with fail-closed behavior;
- no trust promotion/publication.

### Authority

Lifecycle commands are behind the existing manually enabled Experimental RiftCLI.

No new MCP tool, relay method, raw Android shell, model backend, persistent enable flag, trusted-checkpoint promotion or publishing authority was added.

`help` and `contract` are descriptive. Session/evidence/evaluation commands require process-local experimental enablement.

### Evidence ordering

The lifecycle requires:
1. base-bound repository understanding;
2. research;
3. design/document intent;
4. patch;
5. documentation audit;
6. code audit;
7. security;
8. dependency/supply-chain audit;
9. tests when source/build config changed;
10. optional build evidence when available (becomes required/current if supplied; Patch 13 will own mandatory artifact handshake);
11. E2E;
12. rollback;
13. freeze/evaluation.

Non-research evidence cannot be complete with zero checks. WARN/FAIL/NOT_RUN makes the record incomplete. Missing impact-derived targets also makes it incomplete.

### State-of-the-art evidence additions

Complete dependency evidence must disposition:
- lockfiles;
- SBOM;
- licenses;
- provenance.

Complete build evidence must name:
- builder;
- toolchain;
- source revision;
- artifact SHA-256s.

Research collection itself is not called trusted. The final evaluator must independently re-check critical claims. V1 evaluator/patch-actor IDs are declarative separation metadata, not cryptographically authenticated identities.

### Current roadmap effect

- Patch 6 state-machine/policy core: OBSERVE core implemented.
- Patch 7 research ledger: collection/claim schema implemented; final independent re-check remains evaluator responsibility.
- Patch 8 parity gate: target-coverage/order foundation implemented; semantic truth remains validators/evaluator work.
- Patch 9 impact-derived verification planner: target-selection foundation implemented; no autonomous test/build runner.
- Patch 10 stale-result invalidation: candidate/source/evidence binding implemented; hermetic execution is not.
- Patch 11 enforcement/bypass closure: not implemented.
- Patch 12 verification-bundle foundation: implemented; immutable/hash-chained decision trail not yet implemented.
- Patch 13 Builder provenance handshake: not implemented.
- Patch 14 adversarial graduation: pending.

### Limits

- lifecycle session store: 64 sessions;
- imported evidence: 512 KiB/file, 96 records/session;
- checks: 256/record;
- targets: 2000/record;
- full inventory: 50000 files / 512 MiB;
- evaluation packet: 700 KiB and fails rather than truncates;
- evaluator defects: 256.

### Validation

Focused source contract:
- `scripts/test-rift-cli-patch-lifecycle-v1.mjs`

The source test locks manual OBSERVE authority, lifecycle stages, clean acquisition, research rules, evidence completeness/coverage/order, supply-chain/build evidence, stale-result binding, evaluator separation, source ownership and no MCP expansion.

Native shell on-device does not provide Node, so the new JS regression test cannot be honestly claimed executed locally in this source session. It is wired into root `npm run check` and must run in Builder/source-validation environment. Android/Gradle compile and installed-device abuse remain separate gates.

### Rollback

Remove:
- `RiftCliPatchLifecycleV1.kt`;
- `RiftResearchLedgerV1.kt`;
- Experimental CLI lifecycle branch/status/help additions;
- focused test and docs/source declarations.

Existing Patches 1–5 evidence services remain independent and continue to work.

## Build-validation hotfix — verification marker date contract

Builder run `35371827181` for source `84c0a39c7f7e8e2edf27529b566460dd7ef8f087` passed source integrity and all code/wiring checks, then failed only because `scripts/validate-rift-docs.mjs` still hard-coded `2026-09-17` while the subsystems changed by Patches 1–5 had been correctly re-verified on `2026-09-18`.

The validator now checks verification-marker class plus a valid non-future ISO audit date instead of one global hard-coded date. This preserves per-subsystem verification history and removes the false requirement that untouched source must claim a newer audit date. No runtime, MCP, filesystem, Git, Local Agent or acceptance authority changed.

## Patch 5 — Semantic diff and Project Intelligence V2 impact mapping

### What changed

Added a candidate-bound semantic impact layer that reuses Project Intelligence V2 rather than trusting the patch author to declare affected APIs, callers, tests or documentation.

### Where

Primary source:
- `RiftSourceIntelligenceV2.kt`
- `RiftWorkspaceRecords.kt`
- `RiftToolSandbox.kt`
- `RiftToolHost.kt`

Validation:
- `scripts/test-rift-semantic-impact-v1.mjs`
- exact Gradle Kotlin source snapshot
- Workspace/MCP/Engine/build-validation documentation

### Why

Patch 4 proves exactly which bytes changed, but a physical diff cannot by itself determine which symbols, imports, callers, tests, documentation owners or build surfaces can be affected. The semantic layer must derive that scope independently from the frozen candidate rather than accepting a model-provided list.

### How

`RiftSourceIntelligenceV2` is the single lexical parser used by both normal PI-v2 indexing and before/after semantic comparison. The previous private symbol/dependency parser was removed from `RiftToolSandbox` so the two evidence paths cannot drift.

Workspace Records derives an internal semantic seed from the exact Patch Manifest V1 candidate. The seed includes exact changed-path identities plus bounded before/after source text. It is not an MCP method and cannot accept a caller-selected path scope.

PI-v2 then derives project roots from the changed paths, refreshes the current index, computes added/removed symbols, signature changes, added/removed dependencies, conservative API-surface changes, current dependencies, current dependents, changed-symbol references, relevant tests, documentation ownership from `docs/SOURCE_OWNERSHIP.md`, nearest README fallbacks, and global project docs. Build/config and documentation changes are classified separately.

The deterministic evidence payload is hashed as `semanticImpactSha256` using Patch Manifest canonical hashing. Cache-refresh diagnostics are attached after that hash and are not part of semantic identity.

The process-owned ToolHost exposes only an internal `candidateImpactAsync` seam for the future Local Agent. It is deliberately absent from `tools()`, aliases and MCP backend mappings.

### Bounds and completeness

Semantic seed:
- maximum 4096 changed paths;
- maximum 1024 changed source files;
- maximum 8 MiB combined before/after source text.

Impact:
- maximum 32 derived project roots;
- maximum 1000 changed symbol names in the output;
- maximum 80 changed symbols used for one-pass textual reference discovery;
- maximum 800 references;
- maximum 800 dependency rows;
- maximum 800 dependent rows;
- maximum 300 tests;
- maximum 300 documentation targets;
- maximum 128 changed source targets for test-affinity expansion.

Crossing a bound, missing before/after source text, a truncated PI index, or a truncated semantic delta adds an explicit incomplete reason. The system never silently calls partial impact evidence complete.

### Effects

Patch 5 can discover affected code/tests/docs from the candidate itself and bind that analysis to the candidate hash. It still cannot accept, deny, publish, advance trusted state or block existing workflows. Development mode remains OBSERVE.

The parser is intentionally a bounded lexical Project Intelligence layer, not a compiler AST or proof of correctness. Later validation patches must still require language/compiler/build/tests and may deny incomplete semantic evidence.

### Validation

Source audit verifies one shared parser owner, no returned private parser duplicate, exact candidate-derived seed, bounded incomplete semantics, deterministic impact hashing, ownership-ledger lookup, internal ToolHost routing, unchanged 18-tool MCP catalog, exact source declaration and focused-test wiring.

### Rollback

Remove `RiftSourceIntelligenceV2.kt`, restore the previous private PI-v2 parser in `RiftToolSandbox`, remove candidate-impact/semantic-seed integration, focused test/docs/source declaration, and leave Patch 4 physical manifest evidence intact.

## Patch 4 — Immutable candidate manifest and tamper-evident record chain

### What changed

Added deterministic candidate identity and tamper-evident evidence primitives without enabling patch blocking or trusted-state promotion.

### Where

Primary source:
- `RiftPatchManifestV1.kt`
- `RiftWorkspaceRecords.kt`

Validation:
- `scripts/test-rift-patch-manifest-v1.mjs`
- exact Gradle Kotlin source snapshot
- Workspace/Workspace Records/MCP/build-validation documentation

### Why

Patch Sessions identify writer provenance, but provenance alone does not bind an approval to exact bytes. Patch 4 creates a content-derived candidate identity and a forward hash chain so later validation stages can prove which base tree, result tree, structural changes and retained evidence were evaluated.

### How

The manifest uses deterministic canonical JSON and SHA-256. Tree identity is derived from sorted path/kind/size/content-SHA fields; mtimes and freeze timestamps are excluded. Change-set and structural-diff digests are separate. The sealed manifest contains the operational checkpoint identity, current tree identity, every changed path with before/after content identity, structural identity relations, retained patch-session evidence, current record-chain integrity and the current trusted-checkpoint state.

`freezeCandidate()` is internal-only. It writes a canonical manifest to the private records store under its SHA-256 filename. Existing identical manifests are verified byte-for-byte. No MCP tool exposes freeze or trusted promotion.

New event records are sealed with chain version, chain epoch, previous-record hash and record hash. Legacy pre-Patch-4 event files remain readable but are outside the new chain epoch. Pruning persists a verification anchor and pruned-through sequence before deleting old records. Startup may fast-forward a lagging persisted head only when the retained chain proves the old head is an ancestor; arbitrary mismatches are not silently healed.

Operational checkpoints now carry a recorder sequence. Provenance completeness is decided from checkpoint/pruning sequence boundaries rather than timestamp guesses. Trusted checkpoint fields exist as inert state only; no source path can promote them yet.

### Effects

`rift_workspace_diff` remains read-only but now has source support to report:
- operational checkpoint identity;
- separate trusted-checkpoint state;
- candidate manifest summary;
- record-chain integrity.

Candidate byte changes alter result-tree/change-set identity. Base changes alter base-tree identity. Evidence changes can also alter the manifest SHA, forcing later validation to re-evaluate rather than inherit stale approval.

### Bounds and risks

- maximum frozen manifests: 512;
- maximum one frozen manifest: 8 MiB;
- candidate workspace-path bound: 50000;
- event record retention remains 2000;
- pre-Patch-4 legacy records are not retroactively hash-chained;
- migration from an old operational checkpoint has unknown checkpoint sequence until a new checkpoint is created, so session-evidence completeness must remain false rather than guessed;
- trusted promotion is intentionally absent until the Local Agent policy gate exists.

### Validation

Static source/impact audit verifies deterministic hashing, immutable-by-hash storage, chain sealing/verification, sequence-based pruning evidence, crash-safe head recovery, no MCP freeze mapping, exact Gradle source declaration and focused-test wiring. Android/Gradle compile and device proof remain required before installed-runtime promotion.

### Rollback

Patch 4 can be reverted by removing `RiftPatchManifestV1.kt`, its Workspace Records integration, test/docs/source declaration and private-manifest/chain state readers. Existing private manifest/event files are non-workspace metadata and must not be treated as workspace source during rollback.

## Patch 3 — Patch sessions and provenance

### What changed

Added bounded writer provenance that correlates MCP, native Shell, native Editor, Dev Lab and native Git mutations with asynchronous Workspace Records events.

### Where

Primary source:
- `RiftPatchSessions.kt`
- `RiftWorkspaceRecords.kt`
- writer integrations in `RiftToolSandbox.kt`, `RiftNativeShell.kt`, `RiftNativeWorkspaceApps.kt`, `RiftNativeDevLab.kt`, `RiftNativeGit.kt`
- optional `intent` schema field in `RiftToolHost.kt`

Validation:
- `scripts/test-rift-patch-sessions.mjs`

### Why

FileObserver delivery occurs after a writer may have returned. A simple thread-local or “last writer” value could falsely attribute an unrelated later change.

### How

Writers declare bounded target paths before mutation, capture before-state, and commit short-lived claims after successful mutation. Exact file/deletion claims are correlated against resulting state and SHA-256. Directory replacement flows are explicitly lower-confidence scope claims. Claims expire after 15 seconds and are bounded. Unknown writers are recorded as `unattributed-local`; provenance is never guessed.

Only explicit `workspace/...` and `D:/Workspace/...` forms enter workspace provenance, preventing unrelated RiftFS paths from stealing workspace events.

### Effects

Workspace Records events now contain `patchId` and structured provenance. Dev Lab and Git receipts may surface patch IDs. MCP tool count remains 18. The optional intent field is evidence only and does not change permission classification.

### Bounds and risks

- 512 paths per provenance session;
- 4096 active claims;
- 15-second claim lifetime;
- intent <=500 characters;
- origin/operation/request labels <=120 characters;
- scope-bound directory claims are not cryptographic authorship proof.

### Validation

Source-first impact audit verified all five writer integrations, exact 43-file Kotlin snapshot at Patch 3 freeze, test/package ownership, strict workspace-only ingress, honest unattributed fallback and unchanged 18-tool MCP catalog.

### Rollback

Remove `RiftPatchSessions.kt`, writer hooks, Workspace Records provenance fields, optional intent schema and focused test/docs. Filesystem behavior itself remains owned by the original writer subsystems.

## Patch 2 — File Identity V2

### What changed

Added bounded rename/copy/rewrite identity correlation to Workspace Records.

### Where

Primary source:
- `RiftFileIdentityV2.kt`
- `RiftWorkspaceRecords.kt`
- relation-aware headers in `RiftDiffEngineV2.kt`

Validation:
- `scripts/test-rift-file-identity-v2.mjs`

### Why

Delete+add records could not distinguish structural movement/copy from unrelated file creation, and large rewrites were reported as ordinary modifications.

### How

Exact SHA-256 equality provides exact content-identity evidence for rename/copy candidates. Remaining text candidates use bounded size-prefiltered line similarity. Full reconciliation is limited to 64 candidates per side and 1024 line comparisons. Heuristic results are explicitly marked non-exact and never treated as user intent.

### Effects

Workspace diff/records can surface renamed, copied and rewritten relationships while preserving raw before/after evidence. No mutation or approval authority was added.

### Bounds and risks

Heuristic matching can remain incomplete when the comparison budget is exhausted. Exact content identity does not prove why a user moved or copied a file.

### Validation

Focused source test locks exact-vs-heuristic semantics, bounds, relation-aware Workspace Records wiring and documentation ownership.

### Rollback

Remove `RiftFileIdentityV2.kt` and relation wiring; Workspace Records falls back to independent path changes.

## Patch 1 — Diff Engine V2

### What changed

Replaced Workspace Records' one-middle-block text diff with a deterministic bounded multi-hunk engine.

### Where

Primary source:
- `RiftDiffEngineV2.kt`
- `RiftWorkspaceRecords.kt`

Validation:
- `scripts/test-rift-diff-engine-v2.mjs`

### Why

The previous prefix/suffix algorithm collapsed widely separated edits into one giant replacement and could obscure independent changes.

### How

Normal regions use exact LCS with the actually allocated `(n+1) x (m+1)` matrix capped at 250000 cells. Larger regions use patience-style unique-line anchors with longest-increasing-subsequence ordering and bounded recursion. Huge ambiguous regions fall back to replacement blocks rather than unbounded quadratic allocation.

### Effects

Widely separated edits produce independent hunks. Empty-file creation/deletion and byte-only line-ending changes remain visible. Binary/oversized files remain hash/metadata evidence.

### Bounds and risks

Rendered output remains capped at 64000 characters and 420 changed lines. Large ambiguous regions may intentionally use a non-minimal replacement fallback to protect low-memory Android devices.

### Validation

Focused source test locks algorithm bounds, multi-hunk structure, Workspace Records delegation, source declaration and docs ownership.

### Rollback

Remove `RiftDiffEngineV2.kt` and restore the prior Workspace Records text-diff routine, with the known loss of independent-hunk behavior.


## 2026-09-18 — Rift++ 0.8.0 Gate 1A scalable storage/view candidate

### What changed

- advanced the active Rift++ bootstrap compiler implementation to `0.8.0-bootstrap` while keeping source language `riftpp/1`;
- added persistent `Buffer<T,N>` with capacity up to 100000;
- added read-only zero-copy `Slice<T>` views;
- preserved frozen `Vec<T,N>` capacity/behavior at <=256;
- added RiftVM Buffer/Slice opcodes and persistent 32-way trie Buffer storage;
- Buffer/Slice remain data-only composites and cannot cross generic host-import boundaries;
- Buffer/Slice checkpoint persistence is explicitly denied;
- added `rift-tool semantic-compat` for ongoing frozen-semantic regression checks while leaving `gate0-verify` as the archival exact-reference/drift check;
- updated focused Core/VM/shell tests and strict wiring validation.

### Why

The self-hosted compiler needs token, AST and instruction storage far beyond Vec-256. A separate scalable persistent storage primitive preserves old Vec semantics while providing compiler-scale indexed storage without prematurely introducing pointer/ownership semantics.

Immutable Buffer versions make zero-copy Slice views safe: a Slice references one Buffer version and does not change when later Buffer updates produce a new version.

### Verified before this record

- direct 600-item Buffer/Slice execution passed with stable-view semantics;
- 20000-item Buffer stress passed at 520033 VM steps under the existing 1000000-step VM hard ceiling;
- ordinary compiled 100000-step budget rejected that stress workload as expected;
- full frozen Rift++ semantic compatibility suite passed after Buffer and after Slice;
- RiftLLM+ regression compile remained green;
- RiftOS audit/scan found no new Gate 1A issue beyond the pre-existing RiftSecretStore filename heuristic.

### Remaining promotion gate

Builder/APK/device proof remains required. After installation, run `rift-tool semantic-compat`, the Gate 1A functional fixture, `riftpp self-test`, and host-boundary checks before freezing Gate 1A.


## 2026-09-18 — Rift++ 0.9.0 Gate 1B text/numeric candidate

### What changed

- advanced active compiler implementation to `0.9.0-bootstrap` while retaining source language `riftpp/1` and `rift-exec-v1 / riftvm-1`;
- added `SourceText`, `TextCursor`, persistent `StringBuilder<N>`, numeric text parse/format;
- selected UTF-16 code units for the hot SourceText/cursor/builder representation;
- kept UTF-8 explicit at file/token/provenance/interchange boundaries through on-demand byte accounting;
- preserved frozen compatibility-string UTF-16 code-unit semantics;
- denied SourceText/TextCursor/StringBuilder checkpoint persistence and SourceText hashing through `value_sha256`;
- added independent Core and VM tests;
- added fixed `rift-tool text-model-benchmark` so the installed-device UTF-16/UTF-8 representation costs are recorded under a named benchmark instead of relying on an unpreserved historical multiplier.

### Verified source-side

- UTF-16/code-unit functional Gate 1B program PASS;
- edge/half-surrogate program PASS;
- 70,000-code-unit builder PASS;
- numeric parse/format positive/negative behavior PASS;
- full frozen semantic compatibility suite PASS;
- RiftLLM+ consumer regression compile PASS;
- no language/VM ABI version bump required because changes are additive to valid `riftpp/1` source.

### Promotion boundary

Gate 1B remains **not frozen** until Builder/install/device proof runs `semantic-compat`, exact Gate 1B fixtures, `text-model-benchmark`, self-test, authority-boundary regression and final audit.


## 2026-09-18 — Rift++ Core README maintenance-contract repair

Builder run `35389192989` for source `3c4ed2756ff9874fd011db0b8d22c295109c3efe` failed in `validate-rift-docs.mjs` because `docs/systems/riftpp-core/README.md` was missing the required `## Failure signatures` maintenance heading.

The Gate 1B compiler/runtime code was not the failing gate. The README now restores the required maintenance section with failure-routing signatures derived from the current Core/VM/tooling contracts. The validator itself was not weakened.


## 2026-09-18 — Gate 1B live promotion blockers: UTF-8 determinism + benchmark v2

Installed source `79198ea55704da0e86254ec9e93f93f14611603f` passed semantic compatibility, self-test, primary Gate 1B fixture and host-capability boundaries, but Gate 1B was **not frozen**.

Two blockers were found on-device:

1. `SourceText.utf8_byte_len()` was host-dependent for an unpaired surrogate produced by code-unit slicing. The source-side reference expected canonical U+FFFD UTF-8 length (3 bytes), while the Android/JVM TextEncoder bridge reported 1 byte. RiftVM now computes canonical UTF-8 byte length directly from UTF-16 code units so the result no longer depends on the host encoder.
2. Benchmark v1 was too narrow: it compared raw `charCodeAt` summation with typed-array byte summation. Four live runs consistently favored UTF-8 on that microbenchmark: prepared UTF-8 was roughly 27–30% faster, and UTF-8 prepare+scan roughly 21–23% faster. This result is preserved rather than overridden. Benchmark v2 now separately measures lexer-like sequential traversal and code-unit random access with UTF-8 index preparation/memory cost.

Gate 1B remains pending another Builder/install/device pass with benchmark-v2 evidence.


## 2026-09-18 — Gate 1B benchmark-v2 signed-byte boundary repair

Installed source `35c72ceb49c512ca9fe6a7b667f178f9b4defe05` passed exact-source identity, semantic compatibility, self-test, both positive Gate 1B fixtures, all expected negative diagnostics and capability-boundary tests. Canonical `SourceText.utf8_byte_len()` also matched the 3-byte U+FFFD contract on-device.

The new benchmark-v2 tool itself failed before measurement with `UTF-8 code-unit index length mismatch`. Root cause: the Android QuickJS bridge exposed Kotlin `ByteArray` elements as signed 8-bit values, while the benchmark decoder expected unsigned UTF-8 bytes. The same audit also showed Kotlin/JVM default UTF-8 replacement behavior was not sufficient as the canonical TextEncoder boundary for malformed UTF-16.

The headless runtime now:
- provides one explicit canonical UTF-8 encoder that replaces unpaired UTF-16 surrogates with U+FFFD bytes;
- uses it for all headless UTF-8 byte-limit/hash/state/write paths;
- makes the TextEncoder polyfill normalize bridged bytes into unsigned `Uint8Array` values;
- has focused shell/wiring validation that rejects regression to JVM default `toByteArray(Charsets.UTF_8)` or signed-byte TextEncoder output.

Gate 1B remains unfrozen until the next Builder/install run returns benchmark-v2 measurements.


## 2026-09-18 — Rift++ 0.10 native byte substrate local candidate

After the shared Rift Text reference proved Strict, Replace, streaming, and direct streaming transcode behavior, the next substrate was documented first and then patched locally.

Candidate source:
- compiler `0.10.0-bootstrap`;
- source language remains `riftpp/1`;
- target remains `rift-exec-v1 / riftvm-1`;
- checked `u8` range 0..255;
- existing `Buffer` / `Slice` support `u8`;
- explicit `u8_to_u32`;
- checked `u8_from_u32`;
- u8 participates in checked arithmetic/comparison, display/hash and primitive checkpoint state.

No raw pointer or new host authority was added.

Focused Core/VM tests and strict wiring validation were updated. Builder has no compiler-version pin and already verifies packaged Core/VM bytes against source.

This record does **not** claim runtime PASS: native RiftShell intentionally denies arbitrary Node/process execution and the installed APK still carries the previous compiler. Builder/install/device proof is required before promotion.


## 2026-09-18 — Rift++ bounded u8 device proof route

The existing fixed `riftpp self-test` command was upgraded to schema `riftpp-shell-self-test/3` so the 0.10 native-byte candidate can be proven on-device without adding any generic JS/process execution surface.

The embedded proof covers checked u8 literals/conversions, `Buffer<u8,N>`, `Slice<u8>`, deterministic hashing, compile-time literal overflow rejection and runtime checked arithmetic overflow rejection.

The command still executes with no host imports under the existing Rift++ shell limits. Shell and wiring validators now fail if this bounded u8 proof disappears.


## 2026-09-19 — Native RiftCLI Bootstrap-0 reset

The former Experimental RiftCLI implementation was intentionally retired instead of being used as the foundation for the next CLI architecture.

Retired CLI-only owners:
- `RiftExperimentalCli.kt`;
- `RiftCliPatchLifecycleV1.kt`;
- `RiftDocumentationParityV1.kt`;
- `RiftVerificationPlannerV1.kt`;
- `RiftResearchLedgerV1.kt`;
- `RiftPlusPlusV0.kt`;
- `RiftIrV1.kt` / `RiftIrCliV1.kt`;
- `RiftSwarmCoordinatorV0.kt`;
- `RiftTextEncoderTaskRunner.kt`;
- their Experimental CLI docs, V0 sample and focused regression tests.

Shared RiftOS infrastructure was deliberately retained: Project/Source Intelligence, Workspace Records, Diff/File Identity, Patch Manifest/Sessions, Git, MCP, Dev Lab, RiftBuild, RiftBrowser and Local Agent.

The replacement Bootstrap-0 architecture is:
- C++ canonical core under `android/app/src/main/cpp/riftcli/`;
- thin `RiftCliHost.kt` JNI loader/result adapter only;
- existing `rift-cli` RiftShell command routed directly to that host;
- `riftos-agent` restored to direct `RiftOsLocalAgent` routing with no CLI interception;
- permanent dependency direction `external driver -> MCP/RiftShell -> RiftCLI`;
- no model/API client inside RiftCLI;
- no mutation, tool, network, project-memory, planner or verification authority in Bootstrap-0;
- process-local explicit `CONFIRM-EXPERIMENTAL` enable switch, default OFF;
- explicit UTF-16 <-> standard UTF-8 JNI transcoding;
- primary `arm64-v8a` plus required `armeabi-v7a` support from the first native build.

Gradle now pins NDK `28.2.13676358`, CMake `3.22.1`, both ARM ABI filters and an exact native C++ source snapshot. Source validation was replaced with `test-rift-cli-native-bootstrap.mjs` plus wiring/docs gates.

The public Builder was updated in parallel to install the pinned NDK/CMake, preflight the native contract and require both `lib/arm64-v8a/libriftcli.so` and `lib/armeabi-v7a/libriftcli.so` in the final signed APK while forbidding x86 RiftCLI payloads.

This entry records **source architecture only**. Bootstrap-0 is not promoted until Builder compilation/package/sign/APK verification succeeds and the installed device proves native `rift-cli status`, architecture, enable/disable and restart-reset behavior.


## 2026-09-20 — Codynex MC0 local proof packaging lane

RiftBuild gained a local, bounded proof-packaging entrance for the Codynex MC0 ARM32 machine bootstrap. This is a source-only local candidate; no APK build or Git push was performed while creating this lane.

New architecture:
- RiftOS CMake owns a dumb `codynex_mc0_host` NativeActivity shared library;
- the host maps the exact MC0 seed RW, changes it to RX, invokes it through the frozen ARM32 ABI, provides bounded source/output buffers, changes emitted code RW -> RX, executes generated code and reports PASS/FAIL;
- the host does not parse Codynex source or emit target instructions;
- `riftbuild prepare-codynex-mc0 <codynex-root>` reads the canonical `native/mc0/arm32/mc0_seed.hex`, requires exactly 172 decoded bytes and SHA-256 `3276dcbf29704b1ba7d9d331e7891ceff10d85b16bb7688c62273aeaa3ca311e`;
- prepare extracts only `lib/armeabi-v7a/libcodynex_mc0_host.so` from the installed RiftOS APK and validates it as ARMv7 little-endian ET_DYN;
- the prepared proof stores the compiler authority only as `assets/mc0_seed.bin`;
- package identity is `com.codynex.mc0proof`;
- the existing deterministic APK packer, Android-Keystore APK v2 signer, independent verifier and user-confirmed PackageInstaller remain the downstream pipeline;
- the installer proof-package boundary is now an explicit two-package allowlist: existing `com.riftpp.nativeproof` plus `com.codynex.mc0proof`;
- MC0 is ARM32-only for the first proof so Android selects a 32-bit process for the A32 seed.

The source-oracle project under Codynex `native/mc0/apk-proof` passes the currently installed RiftBuild source validator with `sourceReady=true`. It remains `preparedPackageReady=false` until a future authorized RiftOS build/install contains the new host and the new prepare command is executed.

This change also records the research-order decision that the remaining LR0 live-replacement gates no longer block MC0. LR0 remains the C++ reference/oracle track; MC0 now proceeds independently as the machine-bootstrap truth track.


## 2026-09-20 — Semnexis wiring validator drift repair

Builder run `35497553508` for RiftOS source `01013078d81cdbae7f7371f89e8c6da034910ddb` stopped in `validate-rift-wiring.mjs` before Android compilation.

The Semnexis compiler/runtime was not the failing subsystem. The validator still required historical literal strings `SNIRV2` through `SNIRV6`, while the current compiler constructs versioned SNIR diagnostics dynamically and now exposes frozen V0-V7 compatibility with `SNIRV7` as the latest binary format. The validator also still pinned headless self-test schema `semnexis-bootstrap-self-test/13`, while current headless source emits `/17`.

The wiring gate was updated without changing Semnexis compiler/runtime behavior:
- retain `SNIRV0` compatibility assertion;
- require latest `SNIRV7` / `NATIVE_IR_BINARY_VERSION_V7`;
- require concrete V2-V7 encoder/decoder function ownership;
- retain the existing feature/opcode/runtime hardening assertions;
- update the headless self-test contract to `semnexis-bootstrap-self-test/17`.

This is a validator-parity repair only. The next Builder run remains the compilation/package proof.


## 2026-09-20 — RiftGit GraphQL regression-test escape repair

Builder run `35497823649` for RiftOS source `6772904840c0c21cd7dcf8833086e84f93d72496` stopped in `test-rift-shell-git.mjs` before Android compilation.

`RiftNativeGit.kt` was correct: Kotlin source must escape the GraphQL variable as `\$input` inside the string literal so the runtime payload contains `$input`. The regression test used a JavaScript regex whose `$input` portion was parsed with `$` as an end-of-string anchor, making the assertion impossible to satisfy against the valid Kotlin source.

The test was changed to an exact source-string assertion for `createCommitOnBranch(input: \$input)`. No RiftGit runtime or GraphQL behavior changed.

The next Builder run remains the compilation/package proof.


## 2026-09-20 — active test parity audit after native RiftCLI reset

After Builder exposed two stale source-regression assertions in sequence, the remaining active `npm run check` chain was audited against current source contracts before another build.

Confirmed current:
- Native RiftCLI Bootstrap-0 tests and dual-ABI Gradle/CMake pins;
- retired RiftShell batch tombstone/one-operation MCP contract;
- current RiftBuild/Codynex MC0 source markers, hashes and proof-package ownership;
- retained local-platform compatibility references;
- RiftLLM fixed Provider/training/corpus contracts;
- Rift++ Core `0.10.0-bootstrap` and current RiftVM contracts;
- Semnexis deep compiler/ARM32 tests including frozen SNIRV0-SNIRV7 compatibility;
- bounded QuickJS/Rift++ shell and native shell/WebView-separation tests;
- retained RiftApps/RiftRT package-format reference tests.

One additional stale cluster was found in `scripts/test-semnexis-shell.mjs` before Builder reached it:
- self-test schema was still pinned to `semnexis-bootstrap-self-test/13` instead of current `/17`;
- latest IR format/version was still pinned to SNIRV6/version 6 instead of SNIRV7/version 7;
- compatibility text was pre-Arena/state;
- source-text assertions incorrectly required literal `SNIRV2` through `SNIRV6`, even though current compiler generates intermediate version labels dynamically;
- the shell test duplicated old exact binary/ARM32 byte counts already owned by the dedicated compiler/ARM32 regression suites.

The shell integration test now verifies the current V7 compatibility/export surface and semantic fixture coverage while leaving exact binary byte-size locks to the dedicated compiler/ARM32 tests. No Semnexis compiler/runtime, RiftCLI, Git, RiftLLM or Rift++ runtime behavior changed.


## 2026-09-20 — RiftGit force-flag test drift repair

Builder run `35498648678` for RiftOS source `b5f9353cf9edf3c66537bc0b1f44fda0fbd2600b` passed the Semnexis shell gate and then stopped in `test-rift-shell-git.mjs`.

The Git implementation was current and correct. Native RiftGit uses GitHub GraphQL `createCommitOnBranch` with `expectedHeadOid = remoteSha` for optimistic concurrency. The test still required a historical literal `.put("force", false)` marker that no longer belongs to this GraphQL input shape.

The regression test now asserts the current invariant:
- `expectedHeadOid` must be present and bound to the remote head;
- the atomic push body must expose no force override.

All other positive `RiftNativeGit.kt` assertions in the test were checked against current source; 97 assertions were evaluated and this was the only stale one.

No RiftGit runtime or GraphQL behavior changed.


## 2026-09-20 — RiftBuild native-test missing CMake binding repair

Builder run `35498910295` for RiftOS source `0db984ccc9f08abe14578cfece36939515e79ae2` passed wiring, transport, docs, CLI bootstrap, Git, path compatibility and earlier gates, then stopped in `scripts/test-riftbuild-native.mjs` with a JavaScript `ReferenceError`.

The test asserted CMake ownership for the Codynex MC0 host:
- `codynex_mc0_host` must be compiled with `-fno-exceptions`;
- `codynex_mc0_host` must be compiled with `-fno-rtti`.

Those CMake rules are present and correct in `android/app/src/main/cpp/CMakeLists.txt`, but the test referenced a `cmake` variable that had never been initialized.

The test now explicitly reads `android/app/src/main/cpp/CMakeLists.txt` before those assertions.

A follow-up undefined-binding sweep of the remaining active tests found no additional comparable missing fixture variable; reported heuristic candidates were all valid local declarations, callback parameters or destructured bindings.

No RiftBuild, Codynex MC0, CMake, RiftCLI or runtime behavior changed.


## 2026-09-20 — escaped Semnexis self-test schema assertion repair

Builder run `35499228200` for RiftOS source `5ec147ed9d4f61ca8dc15e6174479dfec2e765ba` passed all source gates through RiftBuild, RiftLLM, Rift++ Core, Semnexis bootstrap and Semnexis ARM32 execution, then stopped in `scripts/test-semnexis-shell.mjs`.

The previous stale-test audit corrected the executable-result assertion to `semnexis-bootstrap-self-test/17`, but one separate source-regression assertion remained escaped inside a JavaScript regex literal as `semnexis-bootstrap-self-test\/13`. A plain-text search for `semnexis-bootstrap-self-test/13` did not match that escaped representation, so the stale check survived.

A fresh read-only clone of GitHub `main` confirmed the Builder was executing the actual committed file and that line 62 still contained the escaped `/13` assertion. The assertion is now updated to `/17`.

An escape-aware follow-up sweep checked both plain and regex-escaped `/13` through `/16` schema forms across the active test set; no additional old Semnexis self-test schema markers were found in the scanned scripts.

No Semnexis compiler/runtime, RiftCLI, Builder checkout, or Android runtime behavior changed.

## 2026-09-20 — RiftBuild PackageInstaller foreground confirmation fix

Observed on live RiftOS:
- MC0 APK preparation, packaging, APK v2 signing and independent verification all passed;
- PackageInstaller reached `STATUS_PENDING_USER_ACTION`;
- `Intent.EXTRA_INTENT` was present;
- Android did not surface the install confirmation UI.

Root cause:
- the PackageInstaller result `IntentSender` targeted `RiftBuildInstallReceiver`;
- the receiver attempted to launch Android's confirmation intent from a background context;
- modern Android background-activity restrictions can suppress that UI launch.

Fix:
- added private translucent/no-history `RiftBuildInstallActivity`;
- PackageInstaller commit callbacks now use `PendingIntent.getActivity(...)`;
- pending-user-action confirmation is launched from that foreground Activity;
- the existing receiver remains for bounded `PACKAGE_FIRST_LAUNCH` evidence only;
- proof package allowlist, APK v2 verification requirement, user confirmation requirement and exact NativeActivity launch boundary remain unchanged.

No silent install authority was added.



## 2026-09-20 — RiftCLI Gate N1 driver protocol + full-authority delegation

Gate N0 was proven on-device on RiftOS source `6f7a61295d6c75ae97cdde59231d767d39eb8152` / run #250: native C++ status/architecture, `armeabi-v7a` execution, explicit process-local enable, fail-closed unsupported command handling and force-stop/restart reset all passed.

Gate N1 source now adds a native C++ external-driver protocol with:
- session/task/project identity, goal, assumptions and evidence references;
- explicit process-local enable as the authority gate;
- full RiftOS authority while enabled through existing RiftOS owners, not raw Android/Linux escape paths;
- one bounded shell action or one bounded direct `rift_*` ToolHost action per accepted request;
- trusted ToolHost delegation that bypasses user-facing MCP read/write toggles only after native CLI authorization while still using the same confined/audited ToolSandbox;
- hard denial of `rift_shell_exec` and `rift_workspace_exec` inside the direct tool lane so shell recursion and the retired/broken workspace-exec batch path cannot return;
- no embedded model/API client and no direct network/process client in the C++ core.

External-driver continuation is bounded rather than recursively autonomous:
- `loopMax` is capped at 8;
- loops are process-local and reset on enable/disable/process restart;
- loop identity binds session/task/project/loopMax;
- continuations must advance exactly one step;
- the final loop step cannot request more information;
- only a new external-driver request may advance a loop.

RiftShell now owns the N1 dispatch bridge:
- shell dispatch executes one existing native RiftShell command;
- direct tool dispatch invokes the trusted ToolHost lane;
- CLI shell mutations retain `RiftPatchSessions` provenance;
- direct tool mutations continue through ToolSandbox's existing patch/provenance path;
- dispatch failures are returned as structured CLI results instead of silently becoming success.

Focused source validation was expanded with `scripts/test-rift-cli-driver-protocol.mjs` and stronger wiring/bootstrap assertions. The N1 audit also fixed one Kotlin named/positional argument merge hazard in the concurrently modified ToolHost debugger integration.

Source audit/scan after the patch remained clean apart from the existing filename-only `RiftSecretStore.kt` heuristic finding. Builder compile/package and installed-device N1 proof are still required before Gate N1 promotion.


## 2026-09-20 — RiftCLI N1 end-to-end authority hardening

A follow-up end-to-end audit hardened Gate N1 before Builder/device promotion.

Authority execution:
- both RiftShell and direct ToolHost authority lanes now submit process-owned live-poll jobs instead of blocking the MCP request;
- the CLI path has no fixed wall-clock timeout; normal MCP requests retain their existing bounded timeout;
- `rift_cli_job_list`, `rift_cli_job_poll` and `rift_cli_job_cancel` provide external observation/recovery/cancellation;
- job-control requests are idempotent single-step controls and remain available while CLI authority is disabled;
- a shared `RiftCliExecutionGate` permits exactly one outstanding CLI authority job globally across shell + ToolHost, rejecting a second authority action instead of silently queueing future mutations.

Replay/idempotency:
- every authority-bearing driver request requires a bounded `request-id`;
- accepted request IDs are retained without eviction for the entire RiftOS process lifetime;
- duplicate request IDs are rejected;
- the protection set fails closed at 4096 unique authority requests rather than evicting old IDs;
- disable/re-enable clears driver-loop state but does not clear replay protection; replay state resets only with RiftOS process restart;
- lost submit responses can be recovered by listing jobs filtered by the original request ID without replaying the authority action.

Cancellation truthfulness:
- queued cancellation is reported as `cancelled`;
- a running request first reports `cancelling`;
- successful completion after a cancellation request reports `completed_after_cancel_request`;
- interruption after state may already have changed reports `cancelled_may_have_applied`, never a false rollback claim;
- disabling RiftCLI requests cancellation of both authority lanes, while job controls remain available to verify the terminal result.

Retention/privacy:
- both job lanes retain at most 16 jobs for 5 minutes;
- retained terminal output/result is capped at 2 MiB per job;
- oversized successful results become `completed_result_too_large` and retain only bounded metadata;
- job-list recovery is metadata-only; full stored output/result is returned only by explicit poll of a concrete job ID;
- shell job history retains the operation name rather than the full original command/arguments.

Native boundary:
- JNI ingress remains bounded to 512 arguments, 128 KiB per argument, 512 KiB total arguments and 4096 bytes of cwd text;
- explicit UTF-16/UTF-8 transcoding remains in place;
- the native core still owns no direct process/network/model client.

Validation after hardening:
- `test-rift-cli-driver-protocol.mjs` passes against the live working-tree source;
- `test-rift-cli-native-bootstrap.mjs` passes against the live working-tree source;
- JavaScript validator/test syntax and `package.json` syntax pass;
- Rift audit/scan cover 237 files and remain clean apart from the existing filename-only `RiftSecretStore.kt` heuristic finding.

Builder compile/package and installed-device N1 proof remain required before Gate N1 promotion.


## 2026-09-20 — Builder wiring validator class/escape parity repair

Builder run `35505319625` for RiftOS source `0f611a30270e4fafe68eb6bd59f52f772ecf227f` stopped in the first source wiring gate before Android compilation.

Two validator assumptions were stale while runtime source was already correct:

- `AndroidManifest.xml` declares `RiftBuildInstallActivity`, and the class exists in `RiftBuildInstaller.kt`. The wiring gate incorrectly assumed every manifest Activity must live in a same-named Kotlin file (`RiftBuildInstallActivity.kt`). The gate now resolves manifest activities by actual Kotlin class declarations across the Android source set, and checks all Activity declarations in each Kotlin file against the manifest.
- the RiftCLI N1 replay/tool-execution checks compared escaped C++ JSON source using ordinary JavaScript string literals. Quoted JSON string values such as `driverReplayReset` and `driverToolExecution` could lose a backslash during JavaScript literal decoding and falsely report architecture drift. Those assertions now use `String.raw` for the exact C++ source representation.

A follow-up scan of every active `.mjs` test/validator found no additional same-name Activity-file assumptions or non-`String.raw` escaped quoted-value assertions of this class.

No RiftBuild installer runtime, RiftCLI runtime, JNI, authority model, or Android manifest behavior changed. The next Builder run remains the compile/package proof.


## 2026-09-20 — Builder patch-session provenance test parity

Builder run `35506014725` for RiftOS source `c346c3b7d33a8847b7a03130c829fe65e072ee20` passed wiring/docs and then stopped in `scripts/test-rift-patch-sessions.mjs`.

The runtime provenance path was already correct and intentionally generalized for RiftCLI N1:
- `RiftToolSandbox.executeRequest(raw, origin)` owns the shared implementation;
- `RiftPatchSessions.begin(... origin = origin ...)` records the supplied writer origin;
- normal MCP calls use `executeRequest(raw, "mcp")`;
- RiftCLI live-poll jobs use `executeRequest(raw, "rift-cli")`.

The test still required the pre-N1 implementation string `origin = "mcp"`, so it falsely rejected the generalized owner. The test now requires the generalized provenance assignment plus both concrete call-site origins.

A scan across all active `.mjs` tests/validators found no additional hardcoded `origin = "mcp"` provenance assumptions.

No runtime provenance, ToolSandbox, RiftCLI authority, or patch-session behavior changed.

## 2026-09-20 — Codynex MC1-A machine proof lane

Added the next machine-bootstrap pressure stage after the real-device MC0 ARM32 PASS.

RiftOS additions:
- separate `codynex_mc1a_host` NativeActivity test host;
- CMake + exact native-source snapshot wiring;
- bounded `prepare-codynex-mc1a` RiftBuild command;
- frozen MC1-A seed size/hash/package/library constants;
- separate binary manifest encoder for `com.codynex.mc1aproof`;
- exact `assets/mc1a_seed.bin` compiler-authority receipt;
- PackageInstaller allowlist + package visibility for MC1-A;
- RiftShell help and static RiftBuild contract assertions.

MC0 remains a separate frozen proof path. The new lane does not refactor or replace the MC0 compiler artifact.

No general compiler authority, shell authority, silent install authority or heap/runtime semantics were added.

## 2026-09-20 — RiftCLI N1.5 persistent push + N1.6 Batch V2 source completion

RiftCLI pre-N2 work was hardened and re-audited after the live-proven N1 baseline at Builder run #255 / source `121edf6b3255beca33a45351d3952c7026b5cb4b`.

N1.5 persistent push:
- `RiftCliEventBus` now owns a bounded 256-event process-local replay ring, 96 KiB event ceiling and 48 KiB inline-result ceiling;
- event sequences start from a wall-clock-derived high base so a normal RiftOS process restart does not reset new events below a relay/driver cursor retained from the previous process;
- event type and extra metadata keys are bounded;
- batch step coalescing includes `stepId`;
- oversized events collapse to a bounded metadata-only event while preserving their allocated sequence instead of creating a synthetic replay gap;
- the Android relay client forwards `cli.event` over the existing persistent WSS and handles replay requests plus ACKs;
- the relay maintains independent WebSocket/SSE cursors, filters replay already consumed by each subscriber and advances cursors only forward;
- device reconnect uses the oldest active subscriber cursor through `cliResumeAfter`;
- WebSocket and SSE event subscribers share one four-client ceiling;
- slow SSE subscribers fail closed on backpressure rather than building an unbounded write queue;
- Durable Object event payload persistence remains absent; device memory owns replay.

N1.6 RiftCLI Batch V2:
- `rift_cli_batch` accepts at most 16 fully prevalidated sequential steps;
- whole-plan and per-step byte limits, unique step IDs, explicit `validate` / `execute` modes and `stop` / `continue` failure policies are enforced;
- tool steps reject nested/control/batch/workspace-exec targets and shell steps use an explicit allowlist while rejecting recursive `rift-cli` and retired `batch`;
- one Batch V2 job reserves the same global `RiftCliExecutionGate` for its entire plan;
- per-step events include `stepId`, index/count/kind/operation and bounded results;
- `completed_with_failures` is a true terminal push state;
- `InterruptedException` is rethrown rather than being converted to an ordinary failed step, and cancellation is checked before and after every step;
- shell and ToolSandbox mutation provenance use `rift-cli-batch`;
- retired RiftShell `batch` and public multi-op `rift_workspace_exec` remain fail-fast disabled.

Validation/hardening:
- repaired interrupted pre-handoff source tests that contained literal escaped newline text;
- replaced remaining fragile escaped RiftCLI C++ JSON checks in the wiring gate with `String.raw`;
- source-level N1.5 and N1.6 gates pass through the bounded read-only QuickJS harness;
- modified relay and RiftCLI JS/MJS gates parse cleanly;
- same-class scan is clean apart from intentional retired-batch tombstone assertions;
- documentation, roadmap, relay protocol, subsystem READMEs and test inventory were synchronized to push-first + Batch V2 semantics.

This source is not yet promoted as an installed N1.5/N1.6 build. Builder compile/package and target-device push/reconnect/batch proof remain required before N1.7/N2 promotion.



## 2026-09-20 — Codynex MC1-B / MC1.2 proof lane

Added a separate bounded ARM32 proof path for the next Codynex machine-bootstrap pressure stage without modifying the frozen MC1-A oracle.

Changes:

- added `codynex_mc1b_host` NativeActivity test host;
- added exact native-source snapshot ownership for `mc1/codynex_mc1b_host.cpp`;
- added `prepare-codynex-mc1b` RiftBuild command;
- added frozen 552-byte seed / SHA-256 checks;
- added `com.codynex.mc1bproof` bounded binary manifest generation;
- added installer allowlist and package visibility for the MC1-B proof app;
- added exact `assets/mc1b_seed.bin` compiler-authority receipt;
- added source tests requiring 12-byte runtime `MOV + ADD + BX` evidence and 96-check proof coverage;
- retained no-raw-process and bounded-package rules.

MC1-B is not yet claimed as a device pass. A rebuilt RiftOS APK is required so the new ARM32 host can be extracted and materialized into the proof APK, followed by the frozen real-device 96-check run.
