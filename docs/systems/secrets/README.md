# Secret Storage

## Purpose

`RiftSecretStore` provides small encrypted secret-value storage backed by Android Keystore so bearer tokens do not live in ordinary preferences, workspace files or source-controlled metadata.

## Source ownership

`android/app/src/main/java/com/riftos/app/RiftSecretStore.kt`.

Current primary consumer: `RiftRelaySettings` for the optional MCP relay bearer token.

## Design

A Keystore-managed AES key is created/retrieved by `key()`. `set(name,value)` encrypts the UTF-8 value with AES-GCM and stores ciphertext/IV metadata in app-private preferences. `get(name)` decrypts it. `remove(name)` deletes the stored record.

## Why this boundary exists

Configuration such as endpoint/enabled flags can live in normal app preferences; authentication material should be encrypted with a key not stored beside the ciphertext.

## Critical invariants

- Never log plaintext secret values.
- Never export secrets through System Dump, MCP info, repository metadata or `.rift` packages.
- GCM IV/tag handling must remain correct and unique per encryption.
- Decryption failure should fail closed rather than return corrupted plaintext.
- This store is for small secrets, not arbitrary file encryption.

## Failure signatures

- Relay says token missing after upgrade -> secret preference/key alias migration or explicit clear.
- Decryption throws after reinstall/backup restore -> Keystore key no longer matches copied ciphertext; handle as unavailable token and require re-entry.
- Security scanner flags filename alone -> inspect implementation before assuming a committed secret exists.

## Fix map

Encryption/key/persistence -> `RiftSecretStore`.
Relay token lifecycle/when to retain or clear -> `RiftRelaySettings`.

## Validation

Test set/get/overwrite/remove, process restart, invalid/corrupt ciphertext and reinstall/restore behavior. Search generated diagnostics/logs/workspace exports for token leakage.
