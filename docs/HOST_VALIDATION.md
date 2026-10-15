# RiftOS host-only build and release validation

## Manual signed APK workflow

The user triggers the separate `/D:/Workspace/Riftos-builder-main` worker. It checks the exact source SHA and clean checkout, validates a host-only source layout, verifies mandatory Android source ownership, then runs the Gradle Android source/WebView gates, assembles the unsigned APK, zipaligns, signs with the configured private key and verifies the signature and **final packaged** manifest/DEX/assets/native libraries. No source push installs an APK automatically.

The Builder is **not** the independent Core/Shell Builder RAPP and never compiles or stages those components as a side effect of a host build.

## Host checks that remain valid

- Signed package and Android manifest security/permissions; no debuggable shipping APK.
- Correct selected component loader, protected digest/entrypoint/ABI, manual Recovery, no silent promotion.
- Stable host binder interfaces, Core IPC authentication, generic install/FileProvider and native services.
- MCP relay client, Local Agent accessibility, bounded single-operation workspace tools, project export and RiftGit.
- Mandatory Gradle source/declaration-to-final-DEX closure; both Android ARM ABIs and appropriate native assets.
- Exact release checkout, alignment, signing and final artifact hashes.

## Retired embedded validation

Historical **embedded Core/Shell milestones, proof-driven E0/E1/C1 checklists, hardcoded source-string requirements for old desktop/test canaries, unbuilt transition narratives and duplicated build gates** are retired. They should not be mandatory preflight tests merely because frozen compatibility classes remain compiled into the APK.

Retiring those tests does **not** mean removing runtime safety guards, Android permission checks, protected component verification, Recovery integrity or tests of a generic capability actually used by the APK. External Core/Shell unit/device suites now belong with `RiftComponents`.

## Evidence rule

Source inspection and Builder preflight are not evidence of an installed APK. A user-manually signed and installed host, matching source/artifact fingerprints and observed runtime/Recovery behavior constitute device-level proof. Record runtime/version separately from repo HEAD. No TODO trackers belong in this RiftOS source tree.
