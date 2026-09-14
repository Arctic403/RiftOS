# Files App

## Purpose

The Files app is RiftOS's Explorer-style UI over RiftFS. It provides navigation and common file operations while delegating storage semantics to RiftFS/native Android rather than implementing a second filesystem.

## Source ownership

- `openFiles()` and file helpers in `src/riftos.js`.
- Files styling in `src/riftdesktop-android.css` and base `styles.css`.
- filesystem operations in `RiftOSCore.fs` / native dispatcher.
- transfer progress in the transfer subsystem.

## Features

Navigation/address path, list/details presentation, multi-select, selection rectangle/context menu, create file/folder, rename, copy, cut/paste, duplicate, move, delete, archive/extract, mount controls and opening text/previewable entries. Context Open/Paste reuse the same real operation paths as the toolbar. Long-press selects the pressed row and cancels when the pointer moves so scrolling cannot accidentally open a menu. Destination pickers list only immediate child folders, surface listing errors instead of leaving a pending dialog promise, and folder-only Move begins in the current directory. Icon-only navigation controls expose explicit accessibility names so the fixed-scope `riftos-agent` can target Back/Forward/Up/Refresh semantically during device acceptance.

`openFileEntry`/`openEntry` decide whether to navigate into a directory, open a text editor, or hand a file to native preview/open behavior.

## Virtualization

`createVirtualListRenderer()` exists as a reusable virtual-list foundation. The Files renderer must preserve selection, `data-path`, context menus, keyboard/pointer behavior and operation lookups when migrating large directory rendering to virtualization. Do not optimize by dropping interaction state.

## Drive-first navigation and protection

Quick access uses the OS-facing namespace (`D:/Users/Default`, `D:/Documents`, `D:/Downloads`, `D:/Workspace`, `C:/Programs`) while compatibility roots remain valid when opened directly. Protected-root UI checks include both drive aliases and canonical backing roots, matching RiftFS enforcement instead of presenting destructive actions that the backend must later reject.

## Critical invariants

- UI path and selected-path state reflect the canonical RiftFS path.
- File operations call filesystem APIs and await completion before refreshing.
- Large transfers should show progress and not synchronously lock the main UI.
- Selection survives only when entries still exist after refresh.
- Destructive actions operate on the intended selected paths, not stale DOM indexes.
- Context menu actions and toolbar actions should share operation code paths; direct menu paths must not bypass protected-root restrictions.
- Destination pickers must be shallow (`recursive:false`), reject cleanly on filesystem errors, and treat moving to the current parent as a no-op rather than an implicit rename.
- Long-press menus and rubber-band selection must clean up on pointer cancel as well as pointer up; they must not leave global move listeners or stale selection overlays behind.

## Failure signatures

- UI freezes on huge move/copy -> transfer pipeline or unvirtualized/repeated DOM work.
- Operation succeeds but list stale -> refresh/event timing.
- Wrong files affected with multi-select -> selection/path mapping.
- Right edge clipped -> desktop/CSS/insets, not filesystem code.
- ZIP menu visible but fails -> native/archive backend rather than menu placeholder assumptions.

## Fix map

Files UI selection/navigation/action wiring -> `openFiles()`.
Large list rendering -> virtual list integration.
Copy/move throughput/progress -> transfer subsystem/native dispatcher.
Filesystem correctness -> RiftFS.

## Validation

Test empty/large directories, thousands of entries, multi-select, context/toolbar Open/Paste parity, long-press then scroll/cancel, shallow destination navigation, same-folder Move no-op, cut/copy/paste, duplicate, rename, protected-root behavior, delete, archive/extract, mounted folders and navigation during/after long transfers.
