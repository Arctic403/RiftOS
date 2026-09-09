# RiftDev on Android

RiftDev is RiftOS's integrated project editor. The active APK packages `riftdev-android.js` and stores editor workspace data through RiftWorkspace/RiftFS rather than using browser IndexedDB as the Android source of truth.

## Storage bridge

The editor still contains transaction-style IndexedDB calls for compatibility. During Android asset generation those calls are rewritten to `RiftDevAndroidDB`, implemented by `riftdev-android-storage.js` and backed by the parent RiftOS `RiftWorkspace` API.

This means editor files live in the native RiftOS workspace and participate in the same filesystem/patch/export model as the rest of RiftOS.

## GitHub workflow

RiftDev supports manual project pull/push flows. Credentials that need persistence are brokered through Android/RiftOS secret storage rather than being treated as ordinary workspace files.

## AI handoff

RiftDev does not embed an OpenAI API client. Its AI handoff remains file/snapshot based:

1. export the relevant workspace/snapshot,
2. send it to ChatGPT or another patching environment,
3. review/import returned changes,
4. apply locally,
5. explicitly push to GitHub when desired.

The planned RiftScript Studio/Development Snapshot flow in `ROADMAP.md` will expand this into a deeper engine/kernel diagnostic export without granting arbitrary webpages developer authority.

## Preview/testing

The Android Local Test path uses the native RiftOS preview host rather than a service worker. Browser/PWA service-worker test files from earlier editor builds are not packaged into the Android APK.

Cloud/backend test helpers that remain in source are project-specific tooling and are not RiftOS kernel services.

## Security boundary

RiftDev is a privileged RiftOS development app but it still operates through brokered RiftWorkspace/native APIs. Arbitrary downloaded native Android/ELF code is not executed by the editor.
