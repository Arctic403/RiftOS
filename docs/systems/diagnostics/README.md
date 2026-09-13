# Diagnostics and System Dump

## Purpose

The diagnostics subsystem creates a privacy-limited JSON snapshot useful for debugging installed RiftOS builds and exports it through Android's user-controlled Save As flow.

## Source ownership

- `RiftSystemDump.kt` — dump construction and writing.
- `MainActivity.openSystemDumpPicker()` / activity result — `ACTION_CREATE_DOCUMENT` lifecycle.
- `openSettings()` — user-facing trigger.

## Included information

`build()` collects app/build/runtime information, Android/WebView characteristics, process uptime, heap/memory/storage summaries and aggregate RiftFS tree metrics. Tree summarization intentionally aggregates counts/bytes rather than dumping user file content.

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

## Fix map

Diagnostic fields/privacy aggregation -> `RiftSystemDump`.
Picker lifecycle -> `MainActivity`.
Button/UI -> Settings.

## Validation

Inspect a generated dump from a populated device and explicitly verify absence of tokens, file names/content, account data and Android IDs. Test picker cancellation and multiple document providers.
