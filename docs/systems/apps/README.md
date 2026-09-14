# RiftOS Programs and .rift Installer

## Purpose

The app package subsystem is the installation/distribution layer for RiftOS programs. A `.rift` file is an installer package. It is **not** the live execution container and is never launched in an iframe.

## Installed layout

```text
C:/Programs/<app-id>/
  package.json      validated installed package payload
  install.json      installation metadata

D:/Users/Default/AppData/<app-id>/
  storage.json      user/application state

C:/ProgramData/Installer/
  staging/          transaction candidates
  rollback/         temporary previous versions
```

Source projects normally live under D:/Workspace or D:/Projects. Build outputs/packages normally live under D:/Builds and D:/Packages. Installing a package makes a separate installed program copy under C:/Programs.

## Install transaction

`src/riftapps.js` validates the package, writes a complete candidate to `C:/ProgramData/Installer/staging`, moves an existing program to a rollback location when updating, atomically promotes the staged directory to `C:/Programs/<id>`, and removes the rollback copy only after promotion succeeds. A failed promotion attempts to restore the previous installed program.

User AppData is not overwritten by upgrades. Uninstall removes the program directory and that app's AppData only after explicit user confirmation.

## Legacy migration

IndexedDB app records and old `/apps/packages` + `/apps/data` content are one-way migration inputs. Migrated data is copied into the C:/D: layout without deleting the legacy source during V1, allowing rollback to an older RiftOS build.

## Execution boundary

`RiftApps.launch(id)` delegates to `RiftRT.launch(id)`. `riftapps.js` contains no guest iframe execution path and does not own app runtime bridges. RiftRT decides the executable engine; on Android, normal installed HTML-based V1 packages default to the dedicated Android-owned `native-webview` app surface. Worker/WASM/native plugin engines remain explicit RiftRT targets.

This split is intentional:

```text
.rift package -> RiftApps installer -> C:/Programs/<id> -> RiftRT -> native RiftDesktop window/surface
```

Future R.O.P.E compiler outputs can replace the HTML-compatible payload with a compiled Rift ABI without changing the installer/registry or C:/D: layout.

## Package validation

`rift-app-v1` remains a bounded text package in V1. IDs, entry paths, file paths, total package bytes and declared capabilities are validated before install. Traversal components are rejected. Supported declarations include storage, filesystem, network, clipboard, share, notifications, local-build controller and bounded native capabilities.

## Source ownership

- `src/riftapps.js` — validation, transactional install/update/uninstall, migration and program registry/manager.
- `src/riftapps-files.js` — package export/share UX.
- `src/riftrt.js` — execution after installation.
- `android/app/src/main/java/com/riftos/app/RiftNativeAppHost.kt` — Android-owned V1 installed-program surface and native capability broker.

## Invariants

- Import/install never means execute in an iframe.
- Program files live under C:/Programs; user app state lives under D:/Users/Default/AppData.
- Update failure must not silently destroy the previous installed program.
- An installed package cannot choose its install root.
- Ordinary installed-program filesystem grants cannot modify C:/Programs, C:/ProgramData or another app's AppData; those boundaries are enforced again by the native app host.
- Launcher entries are generated from the installed registry, not arbitrary package-provided Android intents.
- Package execution goes through RiftRT and RiftDesktop lifecycle/process ownership.

## Validation

Run `scripts/test-rift-app-import.mjs`. Verify valid generic-MIME `.rift` files install beneath C:/Programs, invalid packages fail before promotion, upgrades preserve AppData, source contains no installed-app iframe path, and launching an installed program delegates to RiftRT.
