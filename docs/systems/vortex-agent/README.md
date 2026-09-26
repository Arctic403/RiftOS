# Fixed-Scope Local UI Agents

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

RiftOS contains fixed-scope Android Accessibility helpers for device acceptance and UI control.

Current fixed application scopes:
- vortex-agent -> com.vortex3d.app
- riftos-agent -> com.riftos.app

A third fixed package, com.samsung.android.honeyboard, is visible only to the bounded RiftOS keyboard companion.

There is no arbitrary package selector.

## Source ownership

Primary:
- RiftVortexLocalAgent.kt — fixed-package agent logic, AccessibilityService, RiftOS self-agent, Samsung keyboard helper.
- RiftNativeShellServices.kt — strict shell argument parsing and direct routing to the fixed RiftOsLocalAgent authority.
- AndroidManifest.xml — Accessibility service declaration.
- res/xml/vortex_agent_accessibility.xml — package/event/content/gesture capability filter.

Related:
- RiftNativeDevLab.kt — native Dev Lab authority reached by fixed RiftOS devlab route.
- MainActivity.kt — active RiftOS Activity needed for browser-inspect/self Back.
- RiftBrowserWindow.kt — active-browser inspection target.
- RiftVortexBridgeClient.kt — separate Binder bridge; not the Accessibility agent.

## Accessibility service boundary

RiftVortexAccessibilityService is protected by:

android.permission.BIND_ACCESSIBILITY_SERVICE

The Accessibility XML fixes packageNames to:
- com.vortex3d.app
- com.riftos.app
- com.samsung.android.honeyboard

The service can:
- retrieve window content;
- report view IDs;
- include not-important views;
- retrieve interactive windows;
- perform gestures.

Those capabilities are broad Android Accessibility capabilities, but source-level agent routing further restricts actions to constructor-fixed package identities.

## Fixed agent operations

RiftScopedLocalAgent supports:
- status
- open
- tree
- click
- tap
- swipe
- type
- back

RiftOS additionally supports:
- type-focused
- browser-inspect
- keyboard
- devlab
- intelligence — internal-only Local Agent host boundary for RiftCLI. The Local Agent validates/bounds CLI arguments, invokes the existing native CLI supervisor, and preserves the native process-local OFF-by-default `CONFIRM-EXPERIMENTAL` gate.
- cli — public bounded test/compatibility namespace. `riftos-agent cli ...` maps to the internal intelligence operation; raw `riftos-agent intelligence ...` remains unavailable. It does not add a second CLI authority or bypass the native gate.
- batch — source-implemented bounded Batch V2 Local Agent namespace. Internal callers use `RiftOsLocalAgent op=batch` with exactly `submit|list|poll|cancel|recover`; the translator hard-maps those actions to the existing `rift_cli_*` driver controls, caps submit plans at 128 KiB, bounds IDs, accepts only `resume|fail|rollback` for recovery, and re-enters `executeCliForLocalAgent`. It has no independent executor or arbitrary tool-name input. The shell-only acceptance-test form is `riftos-agent batch submit-b64|list|poll|cancel|recover`. Dedicated MCP Batch exposure remains closed until this Local Agent layer is installed/live-proven.

RiftCLI is not a sibling agent or separate Android authority. The fixed ownership direction is MCP/RiftShell -> RiftOS Local Agent -> RiftCLI -> existing bounded RiftOS authorities. Both the compatibility `rift-cli` shell entry and `riftos-agent cli ...` test surface route through `RiftOsLocalAgent` before native CLI execution.

The original N1.8.0 freeze condition is satisfied. Post-N2 Batch re-exposure now has B1/B2A/B2B plus Local Agent Batch promoted; Local Agent Batch is Builder/install/live-proven on source `24937bbcfb6d717a5bddbe79de251bd39026dacc`, run number `386`. Dedicated MCP Batch exposure is the next gate and remains closed until implemented and separately proven.

No operation accepts an arbitrary Android package name.

## Activation and foreground stabilization

Before UI actions, the scoped agent:
1. requires the Accessibility service;
2. waits briefly for Accessibility window state to settle;
3. resolves only visible application roots whose package equals the fixed target;
4. launches only the fixed target package when no eligible root exists;
5. polls for a stable root;
6. requires three stable root observations before returning authority.

An input method may own rootInActiveWindow while the target application remains visible. In that case the agent searches visible application windows only for the fixed target package rather than relaunching the app.

## Tree and node-scan bounds

Returned tree rows are limited to:
1024

All scoped application-tree scans are bounded by:
4096 nodes

This includes:
- focused-editable lookup;
- tree traversal;
- semantic target lookup.

When semantic/focused lookup exceeds the scan ceiling, the operation fails instead of silently searching an unbounded Accessibility tree.

tree output reports:
- total_nodes
- scanned_nodes
- scan_limit_reached
- truncated

A tree may therefore be truncated either by requested row limit or by the hard scan ceiling.

## Node-field bounds

Accessibility node string fields returned/compared by the scoped agent are bounded to:
512 characters

This applies to:
- class
- view ID
- content description
- text used in node JSON/semantic matching

Target selectors supplied by shell/API are themselves bounded to 256 characters.

This prevents one abnormal Accessibility node from inflating tree/lookup responses without bound.

## Password protection

Password nodes are never returned with text.

Operations that would act on editable/clickable target nodes call rejectPassword().

Focused typing also rejects password fields.

Samsung Keyboard operations require the focused RiftOS editable control to exist and reject it if Android marks it as a password field.

The local agent therefore does not expose or edit password-field contents.

## Click behavior

For Vortex3D:
- click resolves one unambiguous semantic/exact target;
- walks upward only inside the fixed package to an actionable anchor;
- performs ACTION_CLICK.

For RiftOS self-controls:
- editable targets first receive input focus when needed;
- bounds are read from the fixed RiftOS node;
- click is performed as a physical Accessibility gesture at the node center.

The parent-anchor walk is capped at 64 levels.

Ambiguous semantic targets fail and require a unique content-description or view ID.

## Raw tap and swipe

tap/swipe require:
- finite coordinates;
- full-display bounds;
- a stable fixed-target root;
- every gesture point to lie inside the fixed target application's visible root bounds.

This audit added an additional overlay check.

For each raw gesture point, the agent inspects currently visible Accessibility windows and rejects the point if another visible package window covers the same coordinate.

This prevents a raw fixed-agent gesture from intentionally targeting a visible IME/overlay belonging to another package merely because the target app's rectangular window also spans that coordinate.

Swipe duration is clamped to 50..3000 ms.

Gesture completion is awaited with a 5000 ms timeout and must not be cancelled.

## Text input

type:
- requires a resolved fixed-package editable node;
- rejects password nodes;
- accepts at most 4096 characters;
- uses ACTION_SET_TEXT.

type-focused:
- is accepted only by riftos-agent at shell parsing level;
- requires one visible, enabled, editable, focused RiftOS node;
- rejects password nodes;
- accepts at most 4096 characters.

vortex-agent cannot use the RiftOS-only type-focused shell route.

## Back

For generic fixed-package agent Back:
- the fixed target must first stabilize;
- GLOBAL_ACTION_BACK is then dispatched.

For riftos-agent with an active MainActivity:
- target activity is stabilized;
- MainActivity.onBackPressed() is invoked on the UI thread;
- dispatch has a 2-second wait bound;
- a 400 ms settle delay follows.

This keeps RiftOS self-navigation on its native Activity path.

## Samsung Keyboard helper

Keyboard scope is fixed to:
com.samsung.android.honeyboard

The helper supports only:
- status
- key

It requires:
- visible RiftOS application window;
- one focused editable RiftOS field;
- non-password focused field;
- visible Samsung Keyboard input-method window.

Only clickable keyboard nodes are considered.

Keyboard target labels are capped at 24 input characters.

Raw keyboard-node text/description inspected for matching is capped at 128 characters.

Both RiftOS focused-field scan and keyboard-key scan are capped at 4096 nodes.

Eligible keys are:
- known function keys such as space, enter, done, next, go, search, shift, backspace, symbols and abc;
- or very short labels of at most three normalized characters.

The keyboard helper has no arbitrary suggestion, clipboard or full-tree return API.

## Native Dev Lab route

riftos-agent devlab is routed directly to the already audited native Dev Lab authority.

It does not:
- scrape the Dev Lab UI;
- execute arbitrary shell text through Accessibility;
- convert Dev Lab into generic process execution.

RiftDevLabLocalAgent returns structured native Dev Lab results and reports webViewRequired=false.

## Browser inspection

riftos-agent browser-inspect:
- requires an active MainActivity;
- requires the RiftOS target to be active;
- calls MainActivity.inspectActiveBrowser().

The fixed inspector grammar includes structural/status actions plus bounded active-page editing:
- `riftos-agent browser-inspect edit <selector> <text>` replaces one non-sensitive text control/editor surface;
- `riftos-agent browser-inspect edit-b64 <selector> <base64-utf8>` carries complete UTF-8 source without shell newline/quote loss;
- decoded edit payloads are capped at 256 KiB;
- eligible targets are text-like inputs, textareas and contenteditable editor surfaces;
- password controls and password/secret/token/API-key/authorization-like controls are rejected;
- the bridge dispatches input/change events for framework-backed editors and keeps reset state in-page;
- no arbitrary JavaScript, cookies, storage, headers, innerHTML or control-value readback is exposed.

The separate RiftBrowser audit remains authority for what browser inspection may return/do. `edit` is explicit local authority only; it does not submit forms, click deploy buttons or otherwise add generic browser automation authority.

This local-agent route does not introduce another WebView owner.

## Strict shell grammar

RiftNativeShellServices routes:
- vortex-agent directly to RiftVortexLocalAgent;
- riftos-agent directly to the fixed RiftOsLocalAgent authority.

The retired Experimental RiftCLI router no longer sits in the Local Agent path. Native RiftCLI Bootstrap-0 has no Local Agent authority.

The parser now fails on malformed/extra arguments instead of silently discarding them.

Current rules include:
- help/status/open/back: exact allowed arity;
- tree: zero or one integer argument, 1..1024;
- tap: exactly two numeric coordinates;
- swipe: exactly four coordinates plus optional integer duration;
- click: non-empty target;
- type: target plus non-empty text;
- type-focused: RiftOS-only and non-empty text.

Numeric finiteness is also checked by the agent before gesture use.

## No process/network authority

RiftVortexLocalAgent.kt contains no:
- ProcessBuilder;
- Runtime.getRuntime;
- arbitrary shell executor;
- network socket transport.

Accessibility authority remains UI-local.

The separate Vortex Binder bridge is audited independently.

## Source fixes in this audit

- bounded all scoped Accessibility scans to 4096 nodes;
- bounded returned/matched scoped node string fields to 512 characters;
- bounded parent action-anchor traversal to 64 levels;
- bounded Samsung keyboard/focused scans to 4096 nodes;
- bounded raw keyboard node labels to 128 characters;
- raw tap/swipe points now must remain within the fixed target app window;
- raw tap/swipe points now reject overlap from another visible package window;
- shell parser now enforces exact/valid local-agent argument grammar;
- type-focused shell route is explicitly RiftOS-only;
- transport validator now locks package filter, bounds, password denial and strict grammar.

## Critical invariants

- no arbitrary package selector;
- Accessibility XML package filter remains exactly the fixed three packages;
- Vortex and RiftOS application agents remain constructor-fixed;
- passwords are never returned or edited;
- scans/output remain bounded;
- semantic target ambiguity fails closed;
- raw gestures stay inside the fixed application window and outside another-package overlays;
- shell parsing fails malformed extra/invalid arguments;
- keyboard helper remains Samsung-keyboard-only and requires a focused non-password RiftOS field;
- local agents do not gain process/shell/network authority;
- Dev Lab/browser routes remain delegated to their dedicated audited owners.

## Failure signatures

- caller can provide package name -> scope regression;
- Accessibility packageNames broadens unexpectedly -> service-scope regression;
- any queue traversal becomes unbounded -> resource regression;
- node text/description is returned without field cap -> response-bound regression;
- password text appears in tree or typing path -> sensitive-field regression;
- tap/swipe accepts a point under another package window -> fixed-scope gesture regression;
- ambiguous semantic target is auto-selected -> target-resolution regression;
- malformed tree/tap/swipe arguments are silently defaulted/ignored -> shell grammar regression;
- keyboard helper accepts arbitrary package or suggestion/clipboard surface -> keyboard authority regression;
- ProcessBuilder/runtime shell/network transport appears -> authority expansion.

## Fix map

Fixed application UI authority and Accessibility implementation -> RiftVortexLocalAgent.kt.

Shell grammar/routing -> RiftNativeShellServices.kt.

RiftOS self-agent routing -> RiftNativeShellServices.kt -> RiftOsLocalAgent in RiftVortexLocalAgent.kt.

Service registration -> AndroidManifest.xml.

Package/event Accessibility filter -> res/xml/vortex_agent_accessibility.xml.

RiftOS Dev Lab operations -> RiftNativeDevLab.kt.

RiftBrowser inspection -> MainActivity/RiftBrowser owners.

Vortex Binder/JNI bridge -> RiftVortexBridgeClient.kt.

## Validation

Second source audit must verify:
- manifest service permission/export;
- exact Accessibility packageNames;
- exact fixed target-package constants;
- operation list;
- stable-root package matching;
- 1024 returned tree rows;
- 4096 node traversal bounds on all queue scans;
- 512-character scoped node-field bounds;
- 64-level parent bound;
- password suppression/denial;
- gesture display + app-window + overlay checks;
- 4096 keyboard scan limits and 128-character raw label bound;
- strict shell arity/numeric parsing;
- riftos-agent routes directly to fixed RiftOsLocalAgent with no RiftCLI interception;
- RiftOS-only type-focused;
- native Dev Lab/browser delegation;
- absence of process/shell/network authority.

APK/device validation remains separate and should exercise real Accessibility window/IME behavior after a successful build.
