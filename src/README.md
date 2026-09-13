# RiftOS Web Runtime Source

This directory contains the trusted RiftOS shell/runtime JavaScript packaged into the Android APK. It is not guest webpage code.

## Ownership map

| File | Owning system README |
| --- | --- |
| `riftandroid-entry.js` | `../docs/systems/boot/README.md` |
| `riftandroid-preload.js` | `../docs/systems/boot/README.md` |
| `riftandroid-platform.js` | `../docs/systems/android-host/README.md` |
| `riftcore.js` | `../docs/systems/kernel/README.md` and `../docs/systems/riftfs/README.md` |
| `riftos.js` | shell UI plus Files/Settings/Browser integration; see `../docs/README.md` |
| `riftdesktop-android.js` / `.css` | `../docs/systems/desktop/README.md` |
| `riftdesktop-window-host.js` / `riftdesktop-android-compat.js` | `../docs/systems/desktop/README.md` |
| `riftapps.js` / `riftapps-files.js` | `../docs/systems/apps/README.md` |
| `riftrt.js` | `../docs/systems/riftrt/README.md` |
| `riftruntime.js` | `../docs/systems/runtime-capabilities/README.md` |
| `riftshell-batch.js` | `../docs/systems/shell/README.md` |
| `riftgit.js` | `../docs/systems/git/README.md` |
| `riftworkspace-web.js` / `riftworkspace-android-adapter.js` | `../docs/systems/workspace/README.md` |
| `riftworkspace-live-host.js` | `../docs/systems/workspace/live/README.md` |
| `riftmcp-system.js` | `../docs/systems/mcp/README.md` |

## Rule

Before changing a file here, read its owning system README. If responsibilities or public behavior change, update that README in the same patch. If a new runtime file is added, add it to `docs/SOURCE_OWNERSHIP.md`; documentation validation intentionally fails for unowned active source files.
