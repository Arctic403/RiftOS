# Diagnostics and System Dump

## Purpose

The diagnostics subsystem creates a privacy-limited JSON snapshot useful for debugging installed RiftOS builds and exports it through Android's user-controlled Save As flow.

## Source ownership

- `RiftSystemDump.kt` — dump construction and writing.
- `RiftRendererCrashGuard.kt` — renderer-loss ring buffer, process-uptime clock and Android 11+ historical process-exit diagnostics.
- `MainActivity.openSystemDumpPicker()` / activity result — `ACTION_CREATE_DOCUMENT` lifecycle.
- `openSettings()` — user-facing trigger.

## Included information

`build()` collects app/build/runtime information, including the generated source SHA/build-run fingerprint, Android/WebView characteristics, process uptime, heap/memory/storage summaries, aggregate RiftFS tree metrics, recent privacy-limited renderer-loss events and Android 11+ historical process-exit reasons. Tree summarization intentionally aggregates counts/bytes rather than dumping user file content. Process uptime uses the uptime clock on both sides of the calculation so deep-sleep time cannot masquerade as process lifetime.

## Privacy boundary

System dumps must exclude secrets, tokens, account data, Android identifiers, installed-app lists and user file names/content. The user chooses destination/provider/filename via Android system picker.

## Runtime flow

```text
Settings -> system.dump.save
  -> MainActivity opens ACTION_CREATE_DOCUMENT
  -> user picks destination
  -> RiftSystemDump.save(uri)
  -> ContentResolver writes JSON
  -> result returned to shell
```

## Failure signatures

- Picker never opens/returns -> MainActivity request/result path.
- Empty/invalid JSON -> dump build/save.
- Dump unexpectedly contains user paths/names/secrets -> privacy regression; block release until fixed.
- Storage metrics wrong -> tree summary/root selection.
- RiftOS restarts but the dump cannot distinguish Java/native/signal/low-memory exit -> `RiftRendererCrashGuard.historicalProcessExits` / Android `ApplicationExitInfo` regression.
- Renderer crash occurs but no `crashRecovery.rendererEvents` entry exists -> WebView surface skipped the shared crash guard.

## Fix map

Diagnostic fields/privacy aggregation -> `RiftSystemDump`.
Renderer/process crash history and process-uptime clock -> `RiftRendererCrashGuard`.
Picker lifecycle -> `MainActivity`.
Button/UI -> Settings.

## Validation

Inspect a generated dump from a populated device and explicitly verify absence of tokens, file names/content, account data and Android IDs. Confirm `crashRecovery.rendererEvents` is bounded and contains only surface/crash/priority/timestamp/uptime metadata, and `historicalProcessExits` reports Android reason/status/memory metadata without trace contents. Test picker cancellation and multiple document providers.
