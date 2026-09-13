# Rift App Package System

## Purpose

The app package system installs, validates, stores, launches and exports RiftOS applications using the `rift-app-v1` `.rift` JSON package format. It is the application distribution/registry layer beneath RiftRT and legacy iframe apps.

## Source ownership

- `src/riftapps.js` — package validation, registry, storage, launcher integration, iframe materialization, permission/capability bridge and app manager.
- `src/riftapps-files.js` — portable package export/save helpers and manager UI enhancements.
- `src/riftrt.js` — executes packages that opt into RiftRT through `riftrt.json`.
- `/apps` source directory is currently empty/reserved; installed apps live in RiftFS at runtime rather than as repository source packages.

## Package lifecycle

`installPackageFile(file)` parses imported JSON then calls `installPackageObject(pkg)`. `validatePackage` rejects malformed IDs/files/entry paths and normalizes the package. `RiftAppRegistry` persists package metadata/assets in RiftFS. `refreshLauncher()` creates/removes launcher entries from installed state. `launchInstalled(id)` materializes the package and opens its runtime.

Portable export reconstructs package JSON from installed state and saves it as a `.rift` file through the browser/download path.

## Runtime models

A package without a RiftRT spec can run as an iframe-style app. A package containing `riftrt.json` opts into the RiftRT engine described in the RiftRT README.

## Capability model

App permissions are declared by the package and mapped through `capabilityForPermission`/`requirePermission`. The host bridge handles allowed calls such as storage, filesystem, clipboard, share and notifications according to app permission and platform availability. `notifications.request` requests Android notification permission and `notifications.schedule` queues a bounded in-process delayed notification through the native dispatcher. Injected app HTML receives a scoped token and Content Security Policy; it does not receive arbitrary RiftOS globals.

## Critical invariants

- Package import must validate before writing registry state.
- Asset paths are normalized and may not use traversal.
- App IDs become storage/runtime identities; changing normalization can orphan data.
- Permission checks happen before host operations.
- Exported `.rift` files must round-trip through the importer.
- Do not store GitHub or privileged host credentials inside app packages.

## Failure signatures

- Android file picker greys out `.rift` -> picker/MIME/import entry configuration, not package JSON validation.
- File selectable but install fails -> `validatePackage`/JSON/package contents.
- Installed app absent from launcher -> registry write or `refreshLauncher`.
- App opens blank -> entry/assets/CSP/bridge materialization or runtime engine.
- Exported package cannot re-import -> portablePackage/export fidelity.

## Fix map

Validation/registry/import/iframe host -> `riftapps.js`.
Saving/exporting `.rift` -> `riftapps-files.js`.
RiftRT-specific engine execution -> `riftrt.js`.
Android picker acceptance -> Android host/file chooser.

## Validation

Run `scripts/test-rift-app-import.mjs`. Test a minimal package, multiple assets, invalid traversal, invalid IDs, reinstall/update, export/re-import and both legacy iframe and RiftRT packages.
