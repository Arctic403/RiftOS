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
| `riftdesktop-native-compat.js` | native-window compatibility content renderer; `../docs/systems/desktop/README.md` |
| `riftdesktop-android.js` / `.css` | legacy/fallback desktop; `../docs/systems/desktop/README.md` |
| `riftdesktop-window-host.js` / `riftdesktop-android-compat.js` | legacy/fallback host compatibility; `../docs/systems/desktop/README.md` |
| `riftapps.js` / `riftapps-files.js` | `../docs/systems/apps/README.md` |
| `riftrt.js` | `../docs/systems/riftrt/README.md` |
| `riftvm.js` | `../docs/systems/riftrt/engines/rift-vm/README.md` |
| `riftruntime.js` | `../docs/systems/runtime-capabilities/README.md` |
| `riftshell-batch.js` | `../docs/systems/shell/README.md` |
| `riftgit.js` | `../docs/systems/git/README.md` |
| `riftvault.js` | `../docs/systems/riftvault/README.md` |
| `riftrepo.js` | `../docs/systems/riftrepo/README.md` |
| `riftmemory-control.js` | `../docs/systems/riftmemory/README.md` |
| `riftbuild.js` | `../docs/systems/riftbuild/README.md` |
| `riftlocal-platform.js` | `../docs/systems/riftrepo/README.md`, `../docs/systems/riftvault/README.md`, `../docs/systems/riftbuild/README.md` and `../docs/systems/riftmemory/README.md` |
| `riftllm-bridge.js` | `../docs/systems/riftllm-bridge/README.md` |
| `riftdevlab.js` | `../docs/systems/dev-lab/README.md` |
| `riftworkspace-web.js` / `riftworkspace-android-adapter.js` | `../docs/systems/workspace/README.md` |
| `riftworkspace-live-host.js` | `../docs/systems/workspace/live/README.md` |
| `riftmcp-system.js` | `../docs/systems/mcp/README.md` |

## Rule

Before changing a file here, read its owning system README. If responsibilities or public behavior change, update that README in the same patch. If a new runtime file is added, add it to `docs/SOURCE_OWNERSHIP.md`; documentation validation intentionally fails for unowned active source files.
