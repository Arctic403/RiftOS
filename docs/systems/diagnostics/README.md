# Diagnostics

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Current diagnostic model

RiftOS no longer has the old exported System Dump feature.

The live diagnostics component is:

RiftBrowserRendererCrashGuard.kt

Its current active purpose is browser-renderer crash containment plus a small privacy-limited local event ring.

There is no current user-facing diagnostic export UI.

## Source ownership

Live:
- RiftBrowserRendererCrashGuard.kt
- RiftBrowserAndroidWebViewEngine.kt callers
- RiftBrowserAppHost.kt caller
- RiftBrowserPreviewActivity.kt caller

Retired:
- RiftSystemDump.kt
- system.dump.save
- old Settings/Save-As dump flow

Related evidence owned elsewhere:
- Build/source identity -> native shell/MCP/build metadata
- Workspace change history -> RiftWorkspaceRecords

## Live crash recording callers

Current renderer-loss callers record fixed surface names:
- browser-main
- browser-popup
- installed-app
- preview

Each affected WebView then destroys only its dead renderer surface.

No crash-guard code restarts or replaces the native desktop, shell or MCP process.

## Recorded renderer event fields

Each event contains only:
- surface, truncated to 48 characters;
- didCrash;
- rendererPriorityAtExit;
- epoch timestamp;
- current RiftOS process uptime.

It does not record:
- URL;
- page contents;
- file contents;
- cookies;
- tokens;
- Android account/device identifiers;
- shell command text.

## Event ring

Private preference file:
rift-renderer-crash-guard

Key:
renderer-events

Maximum retained events:
16

On each write:
- only the newest previous 15 events are copied;
- the new event is appended;
- JSON is stored in private SharedPreferences.

## Corrupted-store bound

This audit added:

MAX_EVENT_STORE_BYTES = 32 KiB

Before parsing the existing event JSON:
- UTF-8 size is checked;
- oversized stored text is treated as an empty history.

recent() performs the same bound before JSON parsing.

This prevents corrupted/private preference content from causing an unbounded diagnostic JSON parse.

## recent() helper status

RiftBrowserRendererCrashGuard.recent(context) exists and returns the stored local renderer-event array.

Current source has zero callers.

Therefore:
- the event ring is written live;
- there is currently no user-facing/API reader wired to it.

Do not document a crash-history screen/export as active.

## Historical Android process-exit helper

historicalProcessExits(context, limit=8):
- exists only on Android R+;
- calls ActivityManager.getHistoricalProcessExitReasons for this package;
- clamps limit to 1..16.

Returned metadata may include:
- timestamp;
- reason/reasonName;
- status;
- importance;
- PSS;
- RSS.

Current source has zero callers.

This is a bounded helper API, not a current Settings/MCP/system-dump surface.

## Renderer destruction

destroyDeadWebView():
- removes the dead WebView from its parent if present;
- removes child views;
- destroys the WebView;
- wraps cleanup steps defensively.

The caller owns any higher-level tab/window/app-instance cleanup.

## Process uptime

currentProcessUptimeMs():
- uses Android process start uptime on Android N+;
- returns a non-negative value;
- returns 0 on older supported behavior path.

## Retired System Dump

Current source audit finds:
- no RiftSystemDump class;
- no system.dump route;
- no dump.save route;
- no Settings dump action.

Any old documentation describing Save-As/system dump export is stale.

A future export feature would need:
- explicit native owner;
- exact field inventory;
- secret/content redaction tests;
- bounded output;
- explicit user action.

## Source fixes in this audit

- crash preference JSON now has a 32 KiB read/parse bound;
- recent() uses the same bound;
- validator now locks the 16-event/32-KiB crash-store constraints;
- documentation separates active recording from currently unwired history helpers.

## Critical invariants

- browser crashes remain scoped to browser-owned WebViews;
- no crash path requests shell/native desktop restart;
- event ring stays <=16 records;
- stored diagnostic JSON is bounded before parse;
- crash events remain privacy-limited;
- no user-facing dump/export is claimed;
- helper APIs with zero callers are not advertised as live UI/API surfaces;
- retired System Dump does not return silently.

## Failure signatures

- crash handler calls MainActivity/shell recovery -> renderer/native-lifecycle coupling regression;
- URLs/content/tokens are added to renderer event store -> privacy regression;
- event history grows without bound -> storage regression;
- oversized preference JSON is parsed without bound -> robustness regression;
- docs claim a crash-history screen/export exists -> reachability overclaim;
- RiftSystemDump/system.dump.save reappears without dedicated audit -> retired-surface regression.

## Fix map

Renderer crash record/cleanup -> RiftBrowserRendererCrashGuard.kt.

Browser main/popup caller behavior -> RiftBrowserAndroidWebViewEngine.kt.

Installed-app renderer caller -> RiftBrowserAppHost.kt.

Preview renderer caller -> RiftBrowserPreviewActivity.kt.

Workspace evidence -> RiftWorkspaceRecords.kt.

## Validation

Second source audit must verify:
- exact renderer-loss callers/surface names;
- no native shell/desktop recovery dependency;
- event field inventory;
- MAX_EVENTS=16;
- MAX_EVENT_STORE_BYTES=32 KiB;
- recent/historical helpers have zero current callers;
- historical limit clamps 1..16;
- retired System Dump/routes absent;
- validator includes the event-store bounds.

Builder/device abuse remains separate.
