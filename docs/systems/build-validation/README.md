# Build and Validation System

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

RiftOS uses two separate validation layers:

1. source-owned static/executable checks inside RiftOS;
2. the separate Riftos-builder Android compile/sign/package verifier.

Neither layer replaces installed-device testing.

## Source ownership

RiftOS repository:
- package.json — source-check entrypoint.
- scripts/validate-rift-wiring.mjs — syntax, Android reachability, exact native snapshot, packaging and runtime-wiring checks.
- scripts/validate-rift-transport.mjs — cross-layer security/authority invariants.
- scripts/validate-rift-docs.mjs — documentation trust/ownership/maintenance gate.
- scripts/test-*.mjs — focused active or explicitly retained-reference contracts.
- android/app/build.gradle.kts — Android source snapshot, generated headless assets and WebView ownership preBuild gates.

External Builder mirror audited during this pass:
- workspace/Riftos-builder-main/.github/workflows/riftos-worker.yml
- workspace/Riftos-builder-main/scripts/riftos-build.sh
- workspace/Riftos-builder-main/scripts/verify-riftos-apk.sh

The Builder is a separate repository and is not part of RiftOS SOURCE_OWNERSHIP.

## npm source gate

Root npm run check delegates to check:transport.

Current ordered flow:
1. Builder syntax-preflights the critical source-gate entrypoints before invoking them;
2. validate-rift-wiring.mjs;
3. validate-rift-transport.mjs;
4. validate-rift-docs.mjs;
5. every focused test-rift-*.mjs listed in package.json.

The external Builder also requires package.json to keep the wiring, transport, docs, RiftCLI push, Batch V2 and DebugHub entrypoints reachable from npm run check.

validate-rift-wiring now also auto-discovers every scripts/test-*.mjs and fails if a focused test exists but is not executed by a package script.

Therefore adding a focused test without wiring it into npm run check is a validation failure.

## JavaScript syntax coverage

validate-rift-wiring syntax-checks:
- every src/*.js;
- Android runtime asset JavaScript;
- workspace-live JavaScript;
- relay JavaScript;
- every scripts/*.mjs.

This includes retained/reference JavaScript intentionally kept as migration/regression material.

Passing syntax does not imply a retained module is packaged or live.

The Builder repeats a narrow syntax preflight for the critical source-gate entrypoints before npm run check, so validate-rift-wiring.mjs is not the only mechanism expected to detect its own syntax corruption.

## Android Activity/source reachability

The wiring validator:
- parses manifest Activity declarations;
- requires each declared Activity source to exist;
- identifies Kotlin types reachable by textual source references from manifest Activities;
- fails when a Kotlin source is unreachable from the Android application graph.

This is a static reachability guard, not Kotlin compilation/type resolution.

## Exact mandatory Kotlin snapshot

Current Android source directory contains 47 Kotlin files.

android/app/build.gradle.kts::verifyRiftOsAndroidSources explicitly lists all 47.

During this audit the old list was found to protect only 32 files.

Gradle itself now compares the declared list exactly with the actual top-level Kotlin directory and rejects duplicate, missing or stale entries. The source validator independently performs the same exact-set comparison:
- a live Kotlin file omitted from Gradle -> failure;
- a stale deleted Kotlin path left in Gradle -> failure.

This prevents the final Builder's DEX verification contract from silently lagging behind current native ownership.

## Gradle preBuild gates

preBuild depends on:
- verifyRiftOsAndroidSources;
- validateRiftBrowserWebViewOwnership;
- syncRiftOsWebAssets.

### WebView ownership

Only explicit RiftBrowser-named owners may contain actual WebKit dependencies/WebView XML. Harmless comments or UI text containing the word `WebView` do not count as renderer ownership:
- RiftBrowserAndroidWebViewEngine.kt
- RiftBrowserWindow.kt
- RiftBrowserMcpAppBridge.kt
- RiftBrowserAppHost.kt
- RiftBrowserPreviewActivity.kt
- RiftBrowserRendererCrashGuard.kt

For Kotlin/Java, the Gradle gate strips block comments and full-line comments, then matches WebKit imports or fully qualified `android.webkit` / `androidx.webkit` references. For XML, it matches an actual `<WebView>` element. Matching dependencies outside that owner set are a Gradle build failure, and the six-owner allowlist must exactly equal the source files that currently use WebKit.

### Headless OS-execution assets

syncRiftOsWebAssets copies exactly:
- src/riftpp-core.js
- src/riftvm.js
- src/semnexis-bootstrap.js

into generated assets/www.

It does not package:
- index.html;
- styles.css;
- broad src/**;
- workspace-live/**.

RiftBrowser Android assets remain under android/app/src/main/assets and are merged separately.

## Documentation validator

validate-rift-docs enforces:
- documentation is unverified by default;
- exact verified-subsystem set;
- SOURCE_OWNERSHIP coverage;
- required local source-area READMEs;
- Markdown relative-link validity;
- retired protocol markers absent;
- the RiftCLI N2 federated-memory roadmap remains explicitly roadmap-only;
- the frozen N2 contract still names the canonical-kernel/specialist split, replaceable MemoryStore, SQLite reference backend and RiftStore experiment;
- ROADMAP.md and the N2 spec retain the hard N2.12-before-N3 promotion barrier.

This audit removed the brittle manually complete subsystem list assumption.

The validator now auto-discovers every docs/systems/**/README.md and adds it to the required set before maintenance/trust checks.

Therefore a newly created subsystem README cannot silently escape:
- minimum useful size;
- Source ownership;
- Failure signatures;
- Fix map;
- Validation sections;
- VERIFIED-marker trust enforcement.

This also closes the discovered gap where docs/systems/vortex-bridge/README.md existed but was not in the old requiredDocs array.

## Retained-reference focused tests

Some focused tests deliberately execute retained JavaScript as migration/regression oracles.

They are not live-runtime proof.

Current examples:
- test-rift-shell-batch.mjs — retained RiftShellBatch transaction oracle; now also asserts batch JS is not packaged and native RiftShell has no batch command.
- test-rift-app-import.mjs — retained RiftApps/RiftRT package-format oracle; now asserts those JS implementations are not packaged and current package execution is Android-owned.
- test-rift-path-compat.mjs — retained cross-module C:/D: compatibility oracle.

The scripts index was rewritten to label these honestly.

## Manual proof scripts

scripts/gate6d2-*.js and gate6d3-*.js are not test-*.mjs and are not automatically executed by npm run check.

They are retained/manual proof fixtures.

Frozen RiftLLM+ proof artifacts are not rerun simply because source validation runs.

## Build provenance

Gradle compiles:
- RIFT_SOURCE_SHA
- RIFT_BUILD_RUN_ID
- RIFT_BUILD_RUN_NUMBER

from Builder/environment values, defaulting to local when not supplied.

Native shell/MCP diagnostics consume this provenance.

## External Builder source resolution

The audited Riftos-builder workflow is workflow_dispatch only.

It:
1. resolves the requested private RiftOS ref to an exact commit SHA;
2. checks out that exact SHA;
3. verifies HEAD equals SOURCE_SHA;
4. requires the checked-out tree to have no tracked drift or untracked files;
5. syntax-checks both Builder shell scripts;
6. preflights Builder assumptions against the source Gradle contract (namespace/application ID, compile/target Android 36, minSdk 26, Java 17, release minification off and filename-to-DEX declaration shape);
7. runs the exact source commit's npm run check;
8. runs the dedicated Gradle validation tasks into `gradle-validation.log` before compilation.

Local unpushed workspace changes are never built by that worker.

## External Android build

riftos-build.sh:
- requires signing identity variables;
- runs npm run check;
- runs `verifyRiftOsAndroidSources` and `validateRiftBrowserWebViewOwnership` as a dedicated Gradle validation phase captured in `gradle-validation.log`;
- runs Gradle release assemble with Java 17/Android 36 only after that validation phase passes;
- requires unsigned APK output;
- zipaligns;
- signs with apksigner;
- verifies zip alignment;
- verifies signature/certificate;
- runs verify-riftos-apk.sh against the final signed APK.

A source-check pass therefore does not imply an APK exists.

## Final APK native verification

verify-riftos-apk.sh reads the current Gradle mandatory Kotlin list.

For every listed top-level Kotlin filename it requires the corresponding com/riftos/app class descriptor in packaged DEX.

It also explicitly requires private top-level RiftDevLabLocalAgent and embedded SOURCE_SHA.

For RiftCLI N1.5, the final signed DEX must also retain the passive push-diagnostic markers `riftcli.event-bus`, `mcp.relay`, `event.created`, `cli.event.send`, `relay.ready`, `cli.replay.request`, `cli.replay.send` and `cli.ack`. Mandatory class descriptors prove the Kotlin owners exist; these markers prove the specific event/relay instrumentation survived compilation into the final artifact.

The final DEX smoke also rejects retired native migration descriptors (`RiftShellBridge`, `RiftSystemDump`, `AndroidWebViewBrowserEngine`, `RiftNativeAppHost`, `RiftPreviewActivity`, `RiftRendererCrashGuard`, `RiftNativeDispatcher`, `RiftTransferManifest`) so stale build-cache output cannot silently reintroduce removed native classes.

Because the current Gradle list is exact 47/47 Kotlin sources, including the bounded RiftBuild signer/installer owners and `RiftCodynexBridgeClient.kt`, the Builder consumes that same mandatory native snapshot dynamically rather than maintaining a second stale Kotlin list.

## Final APK asset verification

The Builder verifier was critically stale before this audit.

It still required:
- assets/www/index.html;
- assets/www/styles.css;
- every src file;
- workspace-live files;
- Workspace Records HTML marker.

That directly contradicted current native Gradle packaging and would reject a correct native APK.

This audit replaced the obsolete block.

Current Builder final-APK rules:
- use Build-Tools `aapt2 dump packagename` for packaged application ID and `aapt2 dump xmltree --file AndroidManifest.xml` for compiled minSdk 26 / targetSdk 36 values; the parser accepts AAPT2's decimal (`=26`, `=36`) or typed-hex rendering of those same integers. `badging` is retained only for the non-debuggable release assertion. SDK mismatches print the observed compiled-manifest SDK lines into the private smoke log;
- reject duplicate ZIP entries and unsafe absolute/`..` paths;
- reject packaged Kotlin/Java source, `.git` content and keystore material;
- require assets/www/src/riftpp-core.js byte-for-byte equal source;
- require assets/www/src/riftvm.js byte-for-byte equal source;
- require assets/www/src/semnexis-bootstrap.js byte-for-byte equal source;
- reject every other file under assets/www;
- explicitly reject index.html, styles.css, workspace-live, PWA/service-worker content;
- verify every non-Markdown asset under android/app/src/main/assets byte-for-byte.

## Builder publication

On successful build with publish=true:
- a private RiftOS prerelease tag is created against SOURCE_SHA;
- the verified APK is uploaded;
- upload retries are bounded to three attempts;
- a failed partial release is cleaned up.

On failure with publication enabled:
- private logs are zipped and attached to a private RiftOS prerelease.

Private source checkout and restored signing files are removed in the always() cleanup step.

## Signing behavior

Preferred signing uses four repository secrets:
- RIFTOS_KEYSTORE_B64
- RIFTOS_KEYSTORE_PASSWORD
- RIFTOS_KEY_ALIAS
- RIFTOS_KEY_PASSWORD

Current fallback behavior remains:
- decode source/android/riftos-debug.keystore.b64;
- alias riftosdebug;
- store/key password android.

This fallback is an alpha/development signing identity, not strong production publisher identity.

It remains intentionally unchanged in this audit because current project policy still permits alpha/debug builds when production signing secrets are absent.

A production-distribution policy should require private release signing and remove the fallback separately.

## External dependency/reproducibility limitations

The audited Builder uses version tags rather than immutable commit-SHA pins for GitHub Actions such as:
- actions/checkout@v7
- actions/setup-java@v5
- gradle/actions/setup-gradle@v6

Gradle itself is pinned to 9.5.0 and Android build tools to 36.0.0.

The mutable Action-tag trust surface is a current external supply-chain limitation; this audit does not claim byte-for-byte reproducible Builder infrastructure.

## Verification-marker contract

The documentation validator no longer hard-codes one calendar day as the only valid meaning of `CURRENT`. A verified subsystem may retain the date on which that subsystem was actually source-audited while another subsystem advances independently. The validator requires the correct verification heading/marker class, a real ISO date, and rejects future-dated markers.

Stale-document detection belongs to source ownership, changed-source impact, roadmap/patch-history synchronization and later Local Agent policy evidence—not a global `YYYY-MM-DD` constant that invalidates correctly re-verified docs or forces untouched docs to lie about their audit date.

## Source fixes in this audit

- expanded Gradle mandatory Kotlin snapshot from partial 32-file list to the exact current source set;
- source validator now compares Gradle list exactly to actual Kotlin tree;
- fixed Android README wording that still described a packaged web shell/runtime;
- docs validator now auto-discovers all subsystem READMEs;
- wiring validator now requires every test-*.mjs to be executed by package scripts;
- Patch 1 added `test-rift-diff-engine-v2.mjs`, which locks the bounded adaptive multi-hunk engine, Workspace Records delegation, source declaration and documentation ownership;
- Patch 2 added `test-rift-file-identity-v2.mjs`, which locks exact SHA identity semantics, bounded heuristic correlation, Workspace Records/query integration and ownership;
- Patch 3 added `test-rift-patch-sessions.mjs`, which locks state-bound provenance, honest unattributed fallback, writer integrations, optional MCP intent metadata and ownership/source declaration;
- Patch 4 added `test-rift-patch-manifest-v1.mjs`, which locks deterministic canonical/tree/change-set hashing, immutable private freeze bounds, record-chain/pruning/recovery semantics, checkpoint-sequence evidence, inert trusted state and absence of MCP freeze authority;
- Patch 5 added `test-rift-semantic-impact-v1.mjs`, which locks one shared PI-v2 parser, candidate-derived semantic scope, bounded incomplete-evidence behavior, ownership lookup, deterministic semantic hashing and absence of an MCP impact tool;
- N1.8.0 adds `test-rift-repository-consistency-v1.mjs`, which independently checks stable fact/edge identities, separate content hashes, traversal-order-independent graph hashing, content-bound file facts, metadata-only file representation, bounded/verified observer cache wiring, PI-v2 cache integrity/producer provenance (`RiftSourceIntelligenceV2.VERSION` + trusted Builder source SHA), fail-closed stale/untrusted producer rejection, observable cache load/rejection diagnostics, the existing `project kind=consistency` surface and the prohibition on a second observer-owned source index. N1.8.0 is now installed-promoted after the adversarial cold/warm/content/cache/restart/concurrency/race/bound/path/result-surface matrix and exact-current-build process-boundary proof passed; those defects remain permanent regression coverage. The promoted semantic cache contract was schema v5; N1.8.1 advances it to v6 only because the semantic payload now includes syntax/local-intent evidence;
- N1.8.1 adds `test-rift-integrity-v1.mjs`, which locks the separate read-only integrity lane above the promoted N1.8.0 graph: shared source-analyzer v6, PI cache v9 syntax/local-intent persistence, conservative structural-v4 syntax evidence with bounded Kotlin interpolation matching, recursive JavaScript template masking, executable-code filtering for dynamic import/require calls, and a permanent regex-resume contract that forbids skipping the token immediately after a regex literal, explicit local/ambiguous/external dependency classification, `complete` separate from `clean`, deterministic integrity hashing, bounded focused-seed frontiers and zero mutation authority. Builder execution, install and the adversarial cold/warm/restart/syntax/import/path-drift/frontier matrix have passed on installed source `198a3f31e22a5d385378fee087aa5f115aed6d5a`; N1.8.1 is promoted and these cases remain permanent regression/proof coverage;
- N1.8.2 adds `test-rift-propagation-v1.mjs`, which locks the separate read-only propagation lane: stable line-independent symbol IDs from `path|kind|name|ordinal`, independent signature IDs, language-aware `referenceCodeMask()` filtering of comments/string-like regions, seed-relevant same-file/dependency candidate filtering with `ignoredNameMatches`, a regression-locked contract that applies the 1024-reference bound only after code/definition/seed filtering and immediately before row emission, smallest-containing-symbol caller attribution, resolved/ambiguous type/interface relations, explicit API-surface classification, bounded reverse BFS across dependency/reference/type edges, deterministic `propagationSha256`, preview-vs-analysis separation and zero mutation authority. Builder execution, install and overload/line-shift/signature/caller/ambiguity/false-reference/interface/multi-hop/cycle/exact-bound/restart torture passed on installed source `9cc74b25c94fd3e23e93f64d3d132e65e63fe3a6`; N1.8.2 is promoted;
- N1.8.3 adds `test-rift-cross-boundary-contracts-v1.mjs`, which locks the read-only `project kind=contracts` oracle and its bounded fail-closed scan: Android namespace/applicationId and Gradle/CMake mirrors, manifest component-to-managed-class resolution, `System.loadLibrary` to CMake producer resolution, Kotlin `external fun` / Java `native` to JNI symbol resolution plus reverse JNI-export-to-managed pairing, advertised MCP tool-to-dispatch coverage, exact relay protocol and CLI-event schema mirrors, and the 75s < 90s < 100s < 110s < 120s timeout chain. The regression also locks executable-code filtering, lexical JNI owner depth, protocol/timeout mismatch fixtures, a 240-row findings/evidence preview cap with full counts/hash authority, and partial-scan finding suppression with `partialFindingsSuppressed=true` so file/size/byte/read bounds cannot manufacture missing-member findings from skipped source. Builder/install torture passed through final source `e6de353ead6e9377e36e1602e301e3e8231a5e43`, run `35949668024` / run 322, including exact/+1 bounds, final Patch 10.58 aggregate suppression, warm determinism, first-read restart parity and N1.8.0-N1.8.2 continuity; N1.8.3 is promoted;
- N1.8.4 adds `test-rift-documentation-claims-v1.mjs`, which locks the read-only `project kind=claims` source-of-truth oracle: source/build/runtime authority flows outward to documentation claims, never the reverse; required current docs, relative Markdown targets, source-ownership coverage, trust-policy markers, N1.8 ROADMAP state, PROJECT_STATUS promoted source/run bindings, canonical Observer state, structured TODO/FIXME discovery and deterministic historical classification are checked with no free-form prose inference. The permanent source contract also locks 4096-file, 2 MiB/file, 64 MiB aggregate, 8192-claim, 1024-finding and 240-preview bounds, deterministic `claimsSha256`, mandatory Gradle source declaration, thin claims dispatch, and complete `rift_info.projectViews` metadata. N1.8.4 now also requires `observer/phase-authority.json`: the regression validates schema/order/lifecycle/source/run semantics and proves the Kotlin oracle no longer embeds mutable phase-status constants. Installed run 327 passed the original runtime torture; final installed run 328 / source `be1e3ddedec2512145ec7132c3a47419c139cb4e` passed Builder compilation/signed-DEX proof plus installed missing/invalid authority fail-closed, warm/restart parity and N1.8.0-N1.8.3 continuity. N1.8.4 is promoted;
- N1.8.5 adds `test-rift-proof-obligations-v1.mjs`, which locks the read-only `project kind=proofs` planner over exact candidate-impact evidence. Strong affected-test selection is limited to changed tests, direct dependency/dependent evidence and changed-symbol references; path-affinity tests remain supplemental and cannot satisfy the affected-test obligation. Missing strong evidence must become an unresolved obligation, while incomplete impact, API/build/deletion/multi-project/unclassified risk escalates to deep verification. The planner is evidence-only (`executesVerification=false`), general suites cannot substitute for affected tests, result sets are bounded, and deterministic `proofsSha256` binds the canonical plan. Builder compilation/signed-DEX proof plus installed semantic/bound/determinism/restart torture remain required before promotion. The N1.8.5 hardening pass additionally makes `test-rift-workspace-records.mjs` lock the 256-record rolling history, 60-second Workspace Records budget, tracking-policy-v2 migration, separately persisted bounded candidate-session evidence, watcher-aware currentness/shared ignore policy, makes `test-rift-semantic-impact-v1.mjs` lock the zero-change no-index fast path plus bounded candidate-project-scoped PI refresh and forbid whole-workspace candidate-impact refresh, and makes transport regressions lock the 75/90/100/110/120-second request chain plus 300-second SSE lease;
- RiftLLM training V2 adds `test-riftllm-training-v2.mjs`, which locks the shared frozen B2 authority, canonical V2 pack/index/record ABI, immutable deep-validated generation publication, global source/sample/content dedup, train/validation/challenge group isolation, bounded bottom-k near-dedup comparison, fixed-path threshold qualification with current-input/current-APK evidence binding, adversarial parser proof with repaired-region deep invariant checks, fixed native shell routes, required-source inclusion and `productionPretrainingEligible=false` until explicit freeze/device gates pass;
- Native RiftCLI reset replaced the retired Kotlin lifecycle/swarm/IR test stack with `test-rift-cli-native-bootstrap.mjs`, which locks retired-source absence, thin JNI ownership, exact C++ source snapshot, ARM64+ARM32 wiring, UTF-safe JNI, process-local enablement, external-driver dependency direction and zero mutation/model/network/process authority;
- Native RiftBuild added `test-riftbuild-native.mjs`, which locks workspace/output confinement, prepared binary APK inputs, honest unsigned status and zero process/CLI/MCP authority expansion;
- retained-reference tests now assert their JS implementations remain un-packaged/unwired;
- script index labels retained tests honestly;
- external Builder APK verifier replaced obsolete full-web-shell requirements with exact Rift++ Core/RiftVM asset verification;
- Builder README removed RiftNativeAppHost and old workspace-live packaging claims.
- wiring validation now recognizes the installed-app host's dynamic `https://app-<token>.riftos.local` origin instead of requiring the obsolete literal `app.riftos.local` string;
- native workspace-app WebView exclusion now checks actual WebKit imports/FQNs instead of rejecting harmless documentation/UI text containing the word `WebView`;
- transport validation now recognizes the same dynamic installed-app origin and checks Workspace Records against its dedicated owner/API instead of scanning unrelated `RiftNativeWorkspaceApps` strings such as the Settings word `approved`;
- SOURCE_OWNERSHIP now uses the validator's canonical exact trust sentence (`Ownership does **not** imply...`) so the documentation-trust gate checks meaning and wording consistently;
- focused retained-reference tests for RiftLLM and Workspace Records now explicitly prove those JavaScript/HTML adapters remain un-packaged instead of presenting retained globals/UI as live APK surfaces;
- shell/WebView validation now detects actual WebKit dependencies rather than harmless comments containing the word `WebView`;
- the external Builder now preflights namespace/application ID, compile/target Android 36, minSdk 26, Java 17, release-minification-off and filename-to-DEX assumptions before source tests/Gradle, so future verifier drift fails with a direct stale-Builder error;
- Builder now runs the two Gradle validation tasks in a dedicated pre-compilation phase/log;
- final APK smoke now rejects duplicate/unsafe ZIP entries, source/VCS/keystore leakage and retired native DEX descriptors;
- transport/wiring validation now proves the actual `preBuild` dependency wiring instead of matching implementation text from the WebKit regex, preventing validator self-drift when the ownership matcher changes;
- Android root Gradle pins built-in Kotlin KGP `2.4.10` so `quickjs-kt 1.0.14` (published with Kotlin 2.4 metadata) is compiled by a compatible Kotlin toolchain instead of AGP's lower default KGP;
- Builder preflight independently requires the same KGP 2.4.10 + QuickJS 1.0.14 + coroutines 1.11.0 tuple before source tests/Gradle compilation;
- focused shell/Rift++ tests now lock the `RiftNativeShell` helper structure (one tokenizer + one single-argument confined resolver + `joinDisplay`) and the headless QuickJS script-constant visibility exposed by the first successful Kotlin compilation attempts; the resolver signature is intentionally the normalized-display-path form used by all live shell call sites.

## Critical invariants

- npm check runs before Gradle in Builder;
- every focused test is wired into npm check;
- every subsystem README is discovered by docs validation;
- Gradle mandatory Kotlin list exactly equals current Kotlin source directory;
- Gradle source snapshot and WebKit-owner validation run explicitly before compilation and remain wired into preBuild;
- only Rift++ Core, RiftVM, and the Semnexis bootstrap enter generated assets/www;
- final Builder APK independently proves those three assets byte-for-byte and rejects any additional OS web asset;
- final Builder verifies native DEX/source provenance, alignment and signatures;
- Builder builds a clean exact Git commit, not phone workspace bytes;
- source validation is never called APK/device proof;
- retained-reference tests remain clearly non-live.

## Failure signatures

- Kotlin source exists but not in Gradle mandatory list -> snapshot-gate regression;
- deleted Kotlin path remains in Gradle -> stale snapshot regression;
- subsystem README exists but bypasses docs validator -> discovery regression;
- test-*.mjs exists but npm check never runs it -> test-gate regression;
- Builder verifier requires index.html/styles.css/workspace-live -> stale native-migration regression;
- duplicate/unsafe ZIP path, leaked source/VCS/keystore material or retired native DEX descriptor passes Builder smoke -> artifact-validation regression;
- unexpected assets/www file passes Builder smoke -> packaging regression;
- npm check is moved after Gradle -> waste/gating regression;
- Builder source tree is dirty but proceeds -> provenance regression;
- SOURCE_SHA absent from final DEX when Builder supplied it -> provenance regression;
- zipalign/apksigner verification disappears -> artifact-validation regression;
- docs call retained JS test live proof -> validation/trust regression.

## Fix map

Root source-check sequencing -> package.json.

Source/runtime wiring -> scripts/validate-rift-wiring.mjs.

Cross-layer authority/security -> scripts/validate-rift-transport.mjs.

Docs/trust/ownership -> scripts/validate-rift-docs.mjs.

Focused tests -> scripts/test-*.mjs.

Android preBuild/source/assets/WebView gate -> android/app/build.gradle.kts.

External build execution -> Riftos-builder scripts/riftos-build.sh.

Final APK content/provenance smoke -> Riftos-builder scripts/verify-riftos-apk.sh.

External workflow/signing/publication -> Riftos-builder .github/workflows/riftos-worker.yml.

## Validation

Second source audit must verify:
- package check order;
- every test-*.mjs appears in package commands;
- syntax coverage;
- exact Gradle-vs-Kotlin snapshot;
- preBuild dependencies;
- exact two generated OS assets;
- WebView allowlist;
- dynamic subsystem README discovery;
- SOURCE_OWNERSHIP checks;
- retained test labeling/packaging guards;
- Builder exact-SHA + clean-tree gate;
- Builder npm-check-before-Gradle ordering;
- zipalign/apksigner verification;
- final DEX/native provenance checks;
- final exact two-asset www rule;
- native Android assets byte-match source;
- signing fallback accurately documented;
- external Builder limitations accurately separated from source/device proof.

No npm/Gradle/Builder execution is claimed by this source audit itself.
