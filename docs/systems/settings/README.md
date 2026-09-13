# Settings System

## Purpose

Settings provides user-facing configuration/diagnostic entry points for RiftOS shell/desktop behavior and system tools without making those systems depend on Settings UI internals.

## Source ownership

- `openSettings()` in `src/riftos.js` — shell Settings window.
- desktop settings persistence in `src/riftdesktop-android.js` (`/system/settings/desktop.json`).
- system dump implementation in `RiftSystemDump.kt`.
- separate native Rift MCP configuration Activity in `RiftMcpActivity.kt` is launched as its own system surface, not embedded permission logic in Settings.

## Current responsibilities

Settings exposes system/desktop preferences and System Diagnostics actions, including Save system dump. Desktop-specific controls should call the desktop system's public/persistence path rather than duplicate geometry/wallpaper state.

## Critical invariants

- Settings UI is not the owner of subsystem state; it edits subsystem-owned configuration.
- Sensitive values such as relay tokens are handled by the native MCP Activity/Keystore rather than rendered in generic shell Settings.
- System dump export uses Android Save As and privacy-limited dump data.

## Failure signatures

- Setting changes visually but resets -> persistence path/subsystem restore.
- Desktop preference writes but behavior unchanged -> desktop applies stale/incompatible state.
- Save system dump button fails -> native `system.dump.save`/picker, not general Settings rendering.

## Fix map

Settings layout/action wiring -> `openSettings()`.
Desktop behavior/state -> desktop subsystem.
MCP permissions/relay -> `RiftMcpActivity`.
Diagnostic dump generation/export -> diagnostics subsystem.

## Validation

Change each setting, close/reopen window, restart RiftOS where persistence is expected, and verify subsystem behavior rather than only stored JSON. Exercise dump Save As cancellation and success.
