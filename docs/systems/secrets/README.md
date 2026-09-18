# Secret Storage

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

RiftSecretStore is RiftOS's small-secret persistence boundary.

It encrypts bearer/pairing credentials with an Android-Keystore-managed AES key before storing ciphertext in app-private SharedPreferences.

It is not:
- a general file-encryption API;
- workspace storage;
- browser/localStorage storage;
- a key export surface.

## Source ownership

Primary:
- RiftSecretStore.kt

Current consumers:
- RiftNativeGit.kt — github.token
- RiftRelaySettings.kt — rift.relay.token
- RiftLlmDevClient.kt — riftllm.dev.token
- RiftToolHost.kt — removal of retired rift.bridge.pairingKey compatibility secret

Credential-entry ownership remains with each consumer subsystem.

## Backing stores

Ciphertext/preferences:
rift-secrets private SharedPreferences

Keystore alias:
riftos.android.secrets.v1

The AES key itself is created/retrieved from AndroidKeyStore and is not stored in SharedPreferences.

## Key generation

key():
- loads AndroidKeyStore;
- reuses an existing SecretKey under the fixed alias when present;
- otherwise generates AES through AndroidKeyStore;
- purposes: encrypt + decrypt;
- block mode: GCM;
- padding: none;
- randomized encryption required.

No raw key bytes are exported by RiftSecretStore.

## Plaintext bound

Maximum secret plaintext:
32 KiB UTF-8

set() converts the input to UTF-8 and rejects values above that bound before encryption.

Current consumers are far smaller:
- GitHub token <=512 characters at RiftNativeGit boundary;
- relay pairing token <=4096 characters at RiftRelaySettings boundary;
- RiftLLM pairing token exactly 64 hex characters.

The larger store-level bound leaves consumer headroom without turning SecretStore into arbitrary encrypted blob storage.

## Secret key names

Secret names:
- must be nonblank;
- <=160 characters;
- may not contain C0 control characters or DEL.

The same validation now runs on:
- set;
- get;
- remove.

Invalid key names cannot be used as arbitrary SharedPreferences selectors.

## Encryption record

Cipher:
AES/GCM/NoPadding

Generated IV must be exactly:
12 bytes

GCM authentication tag:
128 bits / 16 bytes

Stored record format:

base64(iv):base64(ciphertext+tag)

Maximum packed record:
64 KiB UTF-8

The packed limit is checked before preference persistence and before decode/parsing.

## set()

set(name, value):
1. validates key name;
2. bounds plaintext;
3. initializes AES-GCM encrypt cipher with Keystore key;
4. requires generated 12-byte IV;
5. encrypts;
6. bounds packed ciphertext record;
7. writes to private SharedPreferences with commit();
8. requires commit success.

A failed preference commit throws:

Encrypted secret could not be persisted

set() does not report success before storage confirms the write.

## get()

get(name):
1. validates name;
2. reads packed record;
3. returns null when no record exists;
4. rejects oversized packed records before Base64 decode;
5. requires exactly two colon-separated fields;
6. decodes IV/ciphertext inside runCatching;
7. requires 12-byte IV;
8. requires encrypted payload size from one GCM tag through plaintext-limit+tag;
9. decrypts/authenticates using AES-GCM;
10. rechecks plaintext size;
11. returns UTF-8 string.

Malformed record, unavailable key, failed authentication or corrupt ciphertext returns null.

No unauthenticated/corrupt plaintext is returned.

## remove()

remove(name):
1. validates name;
2. records whether the secret existed;
3. performs SharedPreferences removal with commit();
4. requires persistence success;
5. returns whether a value existed before removal.

A failed removal commit throws:

Secret removal could not be persisted

This preserves the historical boolean meaning while distinguishing "nothing existed" from "storage failed."

## Reinstall/restore behavior

Android Keystore material is not guaranteed to survive application reinstall or mismatched backup/restore of preferences.

If ciphertext exists but the corresponding Keystore key is unavailable/different:
- GCM decryption fails;
- get() returns null;
- consumer must treat the secret as unavailable and request re-entry.

RiftSecretStore does not attempt weak fallback decryption.

## Consumer boundaries

### Git

RiftNativeGit verifies a candidate token with GitHub before calling secrets.set().

Shell does not transport github.token.

### Relay

RiftRelaySettings stores only the bearer token in SecretStore.

Non-secret relay enabled/endpoint/device-id state lives in its own private preferences.

### RiftLLM

RiftLlmDevClient verifies:
- exact 64-hex token shape;
- fixed provider package/authority;
- provider status call

before storing riftllm.dev.token.

### Retired bridge key

RiftToolHost may remove the old rift.bridge.pairingKey compatibility secret.

That is cleanup/removal only; it is not a current pairing surface.

## No enumeration/export API

RiftSecretStore exposes only:
- set(name,value)
- get(name)
- remove(name)

It has no:
- list-all;
- dump;
- export;
- raw-key;
- raw-ciphertext API.

A caller must already know the exact secret name.

## Source fixes in this audit

- 32 KiB plaintext limit;
- 64 KiB packed-record limit;
- 160-character/control-free name validation shared by set/get/remove;
- exact 12-byte GCM IV validation;
- encrypted payload/tag size validation;
- decrypted plaintext bound;
- set/remove changed from asynchronous apply() to commit-confirmed persistence;
- failed persistence now throws instead of being reported as success;
- transport validator locks these bounds/failure checks.

## Critical invariants

- AES key remains Android-Keystore-owned;
- AES-GCM randomized IV remains required;
- plaintext never goes to ordinary preference values;
- secret values stay bounded;
- ciphertext parsing stays bounded;
- malformed/decryption-failed data returns no plaintext;
- preference write/remove must confirm persistence;
- no secret enumeration/export;
- consumer-specific validation happens before storage where required;
- secrets never migrate to workspace/source/JS storage.

## Failure signatures

- plaintext token appears in SharedPreferences -> encryption boundary failure;
- AES key bytes are stored/exported -> Keystore boundary failure;
- set/get accepts unbounded values/ciphertext -> resource regression;
- corrupted GCM record returns plaintext -> authentication failure;
- set/remove uses apply() and immediately reports success -> persistence regression;
- invalid secret names can select arbitrary preference keys -> key-validation regression;
- localStorage/sessionStorage is introduced for native bearer tokens -> migration regression;
- generic secret dump/list API appears -> exposure regression.

## Fix map

Encryption/key/record persistence -> RiftSecretStore.kt.

Git token lifecycle -> RiftNativeGit.kt / Settings.

Relay token lifecycle -> RiftRelaySettings.kt / RiftMcpActivity.kt.

RiftLLM token lifecycle -> RiftLlmDevClient.kt / Settings.

## Validation

Second source audit must verify:
- AndroidKeyStore AES-GCM configuration;
- fixed key alias;
- 32 KiB plaintext / 64 KiB packed bounds;
- exact 12-byte IV and 16-byte minimum tag logic;
- shared name validation;
- commit-confirmed set/remove;
- fail-closed get behavior;
- exact current consumers;
- no enumeration/export API;
- no JS/workspace secret persistence;
- source validator includes store bounds.

Device validation later should cover set/get/overwrite/remove, process restart, corrupt ciphertext and reinstall/restore behavior.
