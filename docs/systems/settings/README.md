# Settings System

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

Settings is an Android-native RiftOS built-in owned by RiftNativeWorkspaceApps.kt.

Its current responsibilities are intentionally narrow:
- display RiftOS build/source/migration identity;
- manage GitHub authentication through RiftNativeGit;
- manage RiftLLM Dev API pairing through RiftLlmDevClient.

It is not a general OS preference registry.

## Source ownership

Primary:
- RiftNativeWorkspaceApps.kt — Settings UI and background task routing.
- RiftNativeGit.kt — GitHub credential verification/storage lifecycle.
- RiftLlmDevClient.kt — RiftLLM provider verification/pairing/status/unpair.
- RiftSecretStore.kt — encrypted secret persistence.
- MainActivity.kt — native app lifecycle through RiftNativeWorkspaceApps.

Separate settings owner:
- RiftMcpActivity.kt — MCP permission/relay configuration.

Settings does not own MCP relay endpoint/token configuration.

## Current visible identity

The Settings window displays:
- BuildConfig.VERSION_NAME;
- BuildConfig.RIFT_SOURCE_SHA;
- native migration status text;
- RiftBrowser as sole approved WebView owner.

These values are informational. They do not alter runtime behavior.

## No generic preference controls

Current Settings contains no controls for:
- theme/accent;
- wallpaper;
- browser options;
- filesystem mounts;
- desktop geometry;
- MCP permissions;
- relay endpoint/token;
- Android process/background settings.

No generic setSetting()/preferences mutation API exists in RiftNativeWorkspaceApps Settings.

Do not document historical Settings controls as live unless a current native control exists.

## GitHub authentication

Visible controls:
- masked GitHub token input;
- Save + Verify;
- Clear;
- Refresh.

Input uses password text variation and is single-line.

Save + Verify:
1. copies the current input to a local String;
2. immediately clears the EditText;
3. refuses blank input;
4. runs verification off the UI thread;
5. calls RiftNativeGit.storeToken(token).

RiftNativeGit:
- bounds token length;
- verifies token against fixed GitHub /user before persistence;
- stores only through RiftSecretStore;
- never returns the token in its result.

Settings displays only authentication status/login/error text.

## GitHub clear

Clear:
- immediately clears the input field;
- calls RiftNativeGit.clearToken() on the settings executor;
- reports whether a secret existed.

This audit wrapped clear in runCatching so secret-store failures no longer leave the UI indefinitely stuck at "Clearing GitHub credential…".

## GitHub refresh

Refresh calls RiftNativeGit.authStatus() in the background.

If a secret exists, authStatus verifies it against GitHub before reporting authenticated.

Settings does not read the raw stored token.

## Git credential ingress boundary

Current source contains exactly one caller of RiftNativeGit.storeToken():

RiftNativeWorkspaceApps Settings.

RiftShell contains no github.token transport.

The Git secret is not copied to localStorage/sessionStorage/QuickJS.

## RiftLLM pairing

Visible controls:
- masked 64-character token input;
- Pair + Verify;
- Unpair;
- Refresh.

Pair + Verify:
1. copies input;
2. immediately clears the EditText;
3. refuses blank input;
4. calls RiftLlmDevClient with op=pair.

RiftLlmDevClient pair():
- requires exactly 64 hexadecimal characters;
- requires the fixed provider authority to resolve to package com.riftllm.app;
- calls provider status using the candidate token;
- persists the token only after that provider call succeeds;
- stores through RiftSecretStore.

The provider authority is fixed:
com.riftllm.app.devlab

No caller-selected package/authority is accepted.

## RiftLLM shell boundary

RiftNativeShellServices explicitly rejects:

riftllm-agent pair ...

with an error saying pairing must be entered in native Settings.

Shell may expose status/unpair and fixed bridge commands, but it cannot accept a pairing token.

Therefore Settings is the only current pairing-token ingress surface.

## RiftLLM IPC bounds

RiftLlmDevClient now bounds both sides of its JSON IPC:
- request JSON <=512 KiB UTF-8;
- response JSON <=512 KiB UTF-8.

The response bound was added during this Settings audit because pair/status are Settings-visible paths and the earlier bridge contract bounded only requests.

The already-verified RiftLLM bridge test/doc was updated at the same time.

## RiftLLM refresh

Refresh performs op=status.

The displayed state distinguishes:
- provider absent/not visible;
- installed but unpaired;
- paired and API reachable;
- token stored but provider API unreachable.

No raw token is shown.

## RiftLLM unpair

Unpair:
- clears the input;
- calls op=unpair;
- removes the secret through RiftSecretStore;
- reports success/failure without displaying prior token material.

## Background execution

Settings and native workspace I/O use a two-slot, no-queue executor backed by a separate watchdog. A stalled provider can occupy at most two workers; additional work fails fast instead of building an unbounded queue.

UI updates are returned through Activity.runOnUiThread().

Every asynchronous UI update checks:
- activity.isFinishing;
- activity.isDestroyed.

RiftNativeWorkspaceApps.destroy() shuts down both the worker pool and watchdog. Settings tasks use a 60-second deadline; Editor I/O uses 30 seconds; Files directory/provider listing uses 20 seconds. Android UI updates are emitted only after terminal completion and only while the Activity remains alive.

RiftLLM `ContentResolver.call()` is additionally isolated behind a capped two-worker IPC pool with a 12-second per-call timeout so a wedged provider cannot permanently own the Settings worker.

## Secret display/log rule

Settings source never intentionally:
- logs token strings;
- places token strings in status labels;
- forwards tokens to RiftShell;
- forwards tokens to QuickJS/browser JavaScript.

Input fields are cleared before asynchronous verification starts.

Errors shown in Settings come from bounded subsystem errors/provider status, not token echoing.

## Separate MCP settings

RiftMcpActivity is a different Android Activity that owns:
- MCP permission controls;
- relay enable/endpoint/token;
- relay reconnect/clear;
- tool-manifest diagnostics.

Those controls are not part of the desktop Settings window and should not be documented as such.

## Retired behavior

The old WebView Settings/System Dump/native-dispatcher surface is retired.

RiftSystemDump.kt is absent.

Current Settings does not expose system.dump.save or a generic native dispatch surface.

## Source fixes in this audit

- Git Clear now catches/report secret-store failures;
- RiftLLM provider response JSON is now capped at 512 KiB;
- RiftLLM bridge test/doc updated to keep its VERIFIED contract synchronized;
- Settings documentation narrowed to actual current controls.

## Critical invariants

- Settings remains Android-native/no WebView;
- Git and RiftLLM token inputs stay masked;
- token fields are cleared before async verification;
- Git token persists only after GitHub verification;
- RiftLLM token persists only after fixed-provider verification;
- pairing tokens cannot enter through RiftShell;
- secrets persist only through RiftSecretStore;
- Settings does not become a generic native dispatcher;
- MCP/relay settings remain owned by RiftMcpActivity;
- request/response IPC bounds stay explicit.

## Failure signatures

- token appears in UI status/log/shell result -> secret leak;
- Git storeToken gains another arbitrary caller -> ingress expansion;
- RiftLLM pair becomes accepted by shell -> pairing boundary regression;
- provider package/authority becomes caller-selected -> provider trust regression;
- token persists before provider verification -> credential validation regression;
- Settings adds generic method/command dispatch -> authority expansion;
- Git Clear failure leaves stale "Clearing…" forever -> UI error-handling regression;
- RiftLLM response can return unbounded JSON -> IPC resource regression;
- docs attribute MCP relay controls to desktop Settings -> ownership drift.

## Fix map

Settings UI -> RiftNativeWorkspaceApps.kt.

Git credential verification/storage call -> RiftNativeGit.kt.

RiftLLM pairing/provider IPC -> RiftLlmDevClient.kt.

Secret encryption/persistence -> RiftSecretStore.kt.

MCP relay/permission settings -> RiftMcpActivity.kt / RiftRelaySettings.kt.

## Validation

Second source audit must verify:
- exact visible Settings sections/buttons;
- no generic setting mutation API;
- password input + immediate clear behavior;
- only one live Git storeToken caller;
- Git verification-before-save;
- fixed RiftLLM provider and 64-hex token;
- shell pair rejection;
- request and response 512 KiB bounds;
- background/UI lifecycle checks;
- executor shutdown;
- separation from RiftMcpActivity;
- no WebView/native-dispatcher/system-dump route.

Builder/device validation remains separate.
