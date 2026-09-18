# RiftBrowser Engine Contract

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

`RiftBrowserEngine` is the renderer-neutral contract used by `RiftBrowserWindow`.

It lets RiftBrowser coordinate renderer Views, navigation, inspection, lifecycle and state without making Desktop depend on Android WebView.

## Source ownership

- `RiftBrowserEngine.kt` — live interface.
- `RiftBrowserWindow.kt` — only live coordinator/consumer.
- `RiftBrowserAndroidWebViewEngine.kt` — only current implementation.

## Contract

Every engine exposes:
- `view`;
- `rendererId`;
- current URL;
- load URL;
- back/forward capability and actions;
- reload;
- desktop-mode toggle;
- bounded inspect;
- state snapshot;
- resume;
- pause;
- destroy.

`rendererId` identifies the concrete backend and is included by the current backend in its state.

## Actual consumer use

`RiftBrowserWindow` directly uses:
- `view`;
- `currentUrl/loadUrl`;
- back/forward methods;
- reload;
- desktop mode;
- inspect;
- state;
- resume/pause;
- destroy.

Some BrowserWindow functions exposing forward/reload/desktop-mode are not currently wired to native UI, but the engine methods are still live parts of the coordinator contract.

## State model

`state()` is pull-based.

The engine interface does **not** define a state-change callback. Browser state is pull-based. The current Android WebView backend has only one implementation-specific notification: main-renderer loss, which lets BrowserWindow perform one bounded same-tab engine replacement. That recovery callback is not part of the renderer-neutral interface contract.

## Renderer replacement boundary

A replacement backend must:
- provide one Android `View`;
- obey BrowserWindow visibility/lifecycle;
- provide accurate history/navigation state;
- implement bounded inspection or explicitly compatible behavior;
- clean resources on destroy;
- avoid taking Desktop/window/shell/MCP authority.

Backend-specific auth, downloads, cookies, network policy and native integration belong to the backend subsystem, not this interface.

## Non-ownership boundaries

Engine contract does not own:
- outer Desktop geometry/visibility;
- native browser controls;
- Android WebView settings;
- MCP page bridge implementation;
- popup/download/file chooser platform policy.

## Critical invariants

- interface remains renderer-neutral;
- Desktop never depends on WebView-specific types;
- BrowserWindow can pause/destroy any engine through the contract;
- state is pull-based at the coordinator boundary;
- no fake callback requirement is documented;
- backend cannot become native shell/filesystem authority through this interface.

## Failure signatures

- WebView-specific method/type added to `RiftBrowserEngine` -> abstraction regression;
- BrowserWindow casts engine to WebView backend -> abstraction regression;
- replacement backend cannot be paused/destroyed through interface -> lifecycle contract failure;
- docs require a state callback not present in interface -> documentation drift.

## Fix map

Interface shape -> `RiftBrowserEngine.kt`.

Coordinator behavior -> `RiftBrowserWindow.kt`.

WebView-specific behavior -> Android WebView backend subsystem.

## Validation

Source verification must compare every interface member to the coordinator and current implementation, verify there is exactly one current implementation, and ensure no WebView-specific dependency leaks into the interface.

Device behavior is validated through the concrete backend audit.
