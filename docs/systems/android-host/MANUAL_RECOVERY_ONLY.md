# Manual-only Core and RiftShell Recovery — source target (2026-10-11)

This supersedes the 2026-10-11 boot-success automatic Core acceptance proposal. **The user owns the choice of active Core/Shell and saved backup.** No software decides whether boot is good or bad for automatic promotion, quarantine, or revision rollback.

## Behavior

- The standalone Android APK's `:riftRecovery` Activity is independently launchable. It never initializes Core or RiftShell.
- **Install:** the user manually stages an independent component and explicitly confirms installation; the APK strictly verifies the manifest, immutable DEX SHA, component ABI and class namespace. Installation changes only the next-start selection. It does not copy, overwrite or create the saved Recovery backup, wait for feature tests, or create a pending automatic acceptance.
- **Boot:** the main Core and the graphical RiftShell processes load precisely the user-selected verified revision. An absent selector means embedded migration component. A failed selected DEX load or initialization fails visibly; no silent fallback to another revision and no automatic SHA quarantine, boot health classification, promotion, or restart-based release ledger change. Existing stale `.booting` files from older builds are not used to classify startup.
- **Save Backup:** the user opens **RiftOS Recovery**, presses **Save selected Core as recovery backup** or **Save selected RiftShell as recovery backup**, then confirms replacing the previous snapshot. The APK verifies the selected SHA and immutable staged DEX plus structural receipt and writes one atomic per-component protected pointer. It does not prove feature health. The built-in embedded fallback always remains separately selectable without an external DEX.
- **Restore:** the user independently opens Recovery after a failed boot, chooses **Restore saved Core/RiftShell backup** or **Restore embedded Core/RiftShell**, and confirms. That action alone updates the exact protected next-start pointer; Core/Shell are not hot-swapped. The user may then restart RiftOS. The saved backup is never silently overwritten during an install or boot.
- **Diagnostics:** Android exits, RAPP failures and Shell restarts can be logged. A Shell service may attempt to reconnect the **same selected Shell** to preserve RAPPs but cannot change component revision; crashes do not trigger version rollback.
- **Safety retained:** same-UID external DEX code is trusted, SHA/ABI and structural receipt re-verification, protected user-confirmed installation, atomic backup record writes, separate independently launchable Recovery, and embedded migration fallback. Nothing requires a third post-boot admin-approval ticket.

## Deployment boundary

This is **source-only** until the user manually runs their signed Builder flow. Installed RiftOS #722 still has the older marker/approval semantics; do not suggest that source changes have remotely updated the device. Legacy release-ledger/acceptance helpers may remain as inert migration compatibility sources but MUST NOT be reachable from the automatic Core/Shell selection, install, or Recovery backup paths. A saved backup is a pointer into the APK-private sealed DEX store; clearing app storage/uninstalling can remove it, so it is **not** an off-device disaster backup.

## Required verification

Source/Builder preflight first. User-manual signed build and real device: (1) Recovery opens independently; (2) select a verified Core and Shell where applicable, without a saved backup; (3) manually Save selected backup and verify matching SHA survives a normal close/reopen; (4) install another verified revision and confirm previous backup SHA does not change; (5) manually restore saved backup then embedded migration fallback; (6) verify no unattended selection changes after normal close, denied feature call or controlled failed-start test. Do not deliberately damage the only working Core/Shell before the separate Recovery launcher and saved backup path are proven.
