# Semnexis QuickJS Bootstrap

## Status

**IMPLEMENTED IN SOURCE / DEVICE PROOF PENDING — 2026-09-19**

Semnexis V0 is bootstrapped by a bounded JavaScript compiler hosted inside RiftOS's existing headless QuickJS runtime. Clang, GCC, CMake, LLD and raw subprocess execution are not part of the active Semnexis bootstrap.

The current path is:

\`\`\`text
.snx source in RiftFS
    -> RiftShell semx
    -> RiftHeadlessJsRuntime
    -> packaged semnexis-bootstrap.js
    -> lexer/parser
    -> deterministic Program Graph
    -> graph verifier
    -> effect/capability solver
    -> Execution Plan
\`\`\`

This is intentionally a bootstrap host. QuickJS is not the target runtime for Semnexis programs and is not intended to remain in the self-hosted compiler path.

## Purpose

The first compiler must exist without requiring an Android-hosted C/C++ compiler. QuickJS is already a bounded, packaged RiftOS dependency, so it can host the first Semnexis compiler with no new executable payload or writable-code execution.

The next major compiler milestone remains self-hosting:

\`\`\`text
QuickJS-hosted Semnexis bootstrap
    -> Semnexis compiler written in Semnexis
    -> Semnexis Native IR
    -> native backend
    -> self-hosted compiler
\`\`\`

## Bootstrap surface

RiftShell exposes only fixed Semnexis commands:

\`\`\`text
semx help
semx version
semx self-test
semx check <source.snx>
semx dump-graph <source.snx>
semx dump-plan <source.snx>
\`\`\`

There is no generic JavaScript command, arbitrary eval surface, process execution, network authority or raw Android shell.

## Semantic contract

The bootstrap currently owns:
- V0 lexer/parser for functions, parameters, locals, integer arithmetic and calls;
- canonical \`i32\` type facts;
- deterministic node/edge allocation;
- NameRef resolution;
- call target and argument validation;
- pure/time effect inference to a fixed point;
- explicit \`time\` capability requirements;
- \`with time\` authority grant only at the V0 application boundary \`main\`;
- graph-derived execution planning;
- graph verification including duplicate-edge rejection, exact argument numbering, expression ownership and NameRef type consistency.

Effects and capabilities remain separate: effects describe observable behavior; capabilities describe authority.

## Source ownership

Maintained live owners:
- \`src/semnexis-bootstrap.js\` — packaged QuickJS bootstrap compiler and semantic verifier;
- \`android/app/src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt\` — bounded QuickJS host and confined RiftFS text-read bridge for \`semx\`;
- \`android/app/src/main/java/com/riftos/app/RiftNativeShell.kt\` — fixed \`semx\` command routing;
- \`android/app/build.gradle.kts\` — packages the bootstrap source under generated APK assets;
- \`scripts/test-semnexis-bootstrap.mjs\` — executable compiler/golden/negative regression suite;
- \`scripts/test-semnexis-shell.mjs\` — host/shell/packaging boundary regression suite.

The separate \`workspace/Semnexis/bootstrap/quickjs/semnexis-bootstrap.js\` file is the language-project mirror of the bootstrap source. During this bootstrap period the two copies are expected to remain byte-identical when both workspaces are available.

## Failure signatures

The bootstrap is invalid if:
- \`semx\` routes through a browser WebView or generic JavaScript evaluator;
- Semnexis source can escape RiftFS confinement;
- the QuickJS host gains process, shell or network authority for Semnexis;
- \`RiftNativeToolchain\`, \`riftclang\` or a Clang payload returns as an active bootstrap dependency;
- identical source produces non-deterministic graph/plan text;
- malformed graph relations, duplicate semantic edges, broken argument numbering or expression ownership pass verification;
- a non-entry function can grant ambient V0 authority with \`with time\`;
- the packaged bootstrap asset is missing from the APK;
- source tests pass but installed-device \`semx self-test\` fails.

## Fix map

- lexer/parser/Program Graph/solver/verifier -> \`src/semnexis-bootstrap.js\`;
- QuickJS host and confined file access -> \`RiftHeadlessJsRuntime.kt\`;
- shell command surface -> \`RiftNativeShell.kt\`;
- APK asset packaging -> \`android/app/build.gradle.kts\`;
- compiler regression tests -> \`scripts/test-semnexis-bootstrap.mjs\`;
- shell/authority regression tests -> \`scripts/test-semnexis-shell.mjs\`;
- language roadmap and self-hosting direction -> \`workspace/Semnexis\`.

## Validation

Promotion requires:
- \`npm run check\` passes;
- smoke source matches the frozen Program Graph and Execution Plan golden text;
- invalid arity, duplicate symbols, missing authority, malformed expressions, unknown capability and unknown names are rejected;
- library capability self-grant is rejected;
- source contract proves \`RiftNativeToolchain.kt\` is absent and \`riftclang\` is not routed;
- Gradle packages \`src/semnexis-bootstrap.js\`;
- full RiftOS audit/security/architecture scans add no new findings;
- installed-device \`semx self-test\` passes;
- installed-device \`semx check workspace/Semnexis/tests/smoke.snx\` matches the source regression counts before calling the bootstrap device-verified.
