# RiftVault

## Purpose

RiftVault is RiftOS's provider-independent durable object layer. The current MVP is fully local-first: content-addressed SHA-256 objects, manifests, verified restore, pins, jobs and local benchmarking live under `/system/riftvault/v1`. Remote R2/TeraBox roles are capability-gated and never reported ready without an installed provider transport.

## Source ownership

- `src/riftvault.js` owns the trusted runtime API and RiftShell command surface.
- `src/riftandroid-entry.js` loads RiftVault before RiftRepo/RiftBuild.
- `src/riftos.js` routes the top-level `rift` family.

The working tree is never used for vault metadata. Provider credentials use the existing Android Keystore-backed native secrets route; shell arguments never contain secrets.

## Storage contract

Objects are immutable at `/system/riftvault/v1/objects/sha256/<prefix>/<hash>`. Metadata, manifests and durable job records are separate. Local restore re-hashes the restored bytes before success is returned. File hashing uses `RiftFS.sha256()` backed by Android streaming SHA-256, so large files are digested in bounded native buffers instead of being base64-loaded across the WebView bridge.

## Failure signatures

- invalid native SHA-256 digest -> inspect the RiftFS/native streaming-hash route before trusting or storing the object.
- R2/TeraBox reports `ready:false` -> expected until a supported native provider adapter exists.
- restore checksum mismatch -> destination is removed and the restore fails.

## Fix map

Object addressing/restore/jobs/providers -> `src/riftvault.js`.
Credential storage -> existing `RiftSecretStore.kt` / native `secrets.*` routes.
Shell dispatch -> `src/riftlocal-platform.js` + `src/riftos.js`.

## Validation

Validate deterministic SHA-256 object IDs, duplicate-object reuse, manifest backup/restore, checksum verification, provider capability reporting, no raw credential output, and exclusion from generic RiftShell atomic batches.