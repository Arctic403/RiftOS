# RiftVault — Retained Content-Addressed Backup Design

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Classification

RiftVault is retained/inactive reference source.

Retained implementation:
- src/riftvault.js

Current Android source contains no native RiftVault owner, command or service, and Gradle does not package src/riftvault.js.

Native RiftShell retires the historical generic RiftLocalPlatform `rift` wrapper that once exposed the local-first family.

## Source ownership

Retained implementation:
- `src/riftvault.js`

Historical aggregate wrapper:
- `src/riftlocal-platform.js`

There is no current native RiftVault owner/service. Live secret storage remains separately owned by `RiftSecretStore.kt`.

## Retained design

The retained JavaScript defines:
- root /system/riftvault/v1;
- SHA-256 object store under objects/sha256;
- object metadata;
- backup manifests;
- backup job records;
- local restore with checksum verification;
- pin/unpin metadata;
- local benchmark helper;
- provider descriptors for local, R2 and TeraBox.

Only the local provider is marked ready in that retained implementation.

R2/TeraBox transport is not active there.

## Historical secret surface

The retained `login r2` helper would prompt for R2 credentials and forward them to the old `core.native.call("secrets.set", ...)` surface.

Because RiftVault is not packaged or reachable in the current APK, this is not a live credential-entry path.

Do not re-enable this retained prompt flow without a fresh secrets/native-provider audit.

## Relationships

Retained consumers:
- src/riftrepo.js
- src/riftmemory-control.js
- src/riftbuild.js
- src/riftlocal-platform.js

These references remain inside the retained JavaScript family.

There is no current Kotlin `RiftVault` reference.

## Activation proof

Verified current state:
- zero Kotlin/native RiftVault references;
- zero native `rift vault` command;
- zero Gradle include for riftvault.js;
- no wildcard src/** packaging;
- legacy generic `rift` wrapper explicitly retired;
- PUBLIC_SURFACES classifies riftvault.js as retained reference code.

## Trust rule

VERIFIED status for this README means the **inactive classification** is verified.

It does not certify the retained JavaScript backup/restore/provider implementation for production reactivation.

## Critical invariants

- riftvault.js remains unpackaged unless deliberately reintroduced;
- retained R2/TeraBox code must not be presented as a live cloud-storage provider;
- no current secrets are routed through retained JavaScript;
- live backup claims require a native/package owner and new audit;
- retained content-addressed semantics are reference material only.

## Failure signatures

- docs advertise `rift vault` as a current shell command;
- Gradle silently packages riftvault.js;
- a native caller begins invoking RiftVault without a dedicated audit;
- R2/TeraBox is described as active because retained provider descriptors exist;
- retained prompt-based credential entry becomes reachable.

## Fix map

Retained design -> src/riftvault.js.

Historical aggregate wrapper -> src/riftlocal-platform.js.

Live secrets -> RiftSecretStore.kt and owning native Settings/service.

## Validation

Second audit must prove:
- retained source exists;
- zero Kotlin/native activation;
- zero Gradle packaging;
- no native shell command;
- all current references are retained/reference-only;
- public-surface docs classify it as retained.
