# RiftOS Android Local Codex

## Goal

Run the coding-agent stack on the Android device without sending inference requests to OpenAI or another model API.

```text
RiftOS WebView UI
      |
RiftHostBridge
      |
      +-- RiftFS (/models, /projects, /home)
      |
      +-- llama-server (APK bundled native executable)
      |      |
      |      +-- GGUF model in RiftFS
      |      +-- 127.0.0.1:11434/v1
      |
      +-- Codex CLI (ARM64 only today)
             |
             +-- --oss
             +-- CODEX_OSS_BASE_URL=http://127.0.0.1:11434/v1
             +-- cwd inside /projects
```

The local HTTP connection is loopback-only. Model inference stays on the device.

## Android execution rule

Android 10+ blocks direct execution of binaries copied into the writable app home directory. RiftOS therefore does **not** execute programs from RiftFS. Executable components are built or downloaded during the APK build, stored under `jniLibs/<abi>/lib*.so`, extracted by Android into `applicationInfo.nativeLibraryDir`, and executed from that immutable location.

User/project/model data remains writable in RiftFS. Code remains immutable in the APK.

## ABI support

| Capability | arm64-v8a | armeabi-v7a (32-bit ARM) |
| --- | --- | --- |
| RiftOS APK | yes | yes |
| RiftFS / Android host | yes | yes |
| llama.cpp `llama-server` | yes | yes |
| local GGUF inference | yes | yes, memory constrained |
| Codex CLI | yes | **not currently available upstream** |

OpenAI's current Codex release matrix publishes Linux executables for x86_64 and AArch64, but not ARMv7. RiftOS reports that accurately at runtime rather than installing an incompatible binary. The 32-bit local inference path remains usable and is intentionally kept separate from the Codex availability check.

## Build

CI performs the full native preparation before Gradle:

1. Install Android NDK.
2. Clone `ggml-org/llama.cpp`.
3. Cross-compile `llama-server` for `arm64-v8a`.
4. Cross-compile `llama-server` for `armeabi-v7a`.
5. Download the official Codex AArch64 Linux-musl release binary.
6. Package the executables as APK native libraries so Android extracts them to an executable location.
7. Build the APK.
8. Inspect the APK and fail CI if either llama-server ABI or ARM64 Codex is missing.

For a local build, set `ANDROID_NDK_ROOT` and run:

```bash
cd android
bash scripts/prepare-native-runtime.sh
gradle :app:assembleDebug
```

## Runtime flow

1. Boot RiftOS Android.
2. Choose a `.gguf` file. Android's document picker copies it into `/models` in RiftFS.
3. Start the model. RiftOS launches the ABI-matched bundled `llama-server` on `127.0.0.1:11434` with model alias `rift-local`.
4. On ARM64, enter a prompt and project directory and run Codex.
5. RiftOS launches bundled Codex with `--oss`, `--local-provider ollama`, model `rift-local`, and `CODEX_OSS_BASE_URL` pointing to the local llama.cpp endpoint.
6. Codex works inside the selected RiftFS project directory.

## Security boundary

- RiftFS path resolution rejects traversal outside the RiftOS private filesystem.
- Arbitrary RiftFS files are never executed.
- The bridge knows only two bundled executables: `llama-server` and `codex`.
- Model server binds only to `127.0.0.1`.
- Codex working directories must resolve inside RiftFS.
- The WebView bridge should only be exposed to trusted RiftOS shell content. Do not expose `RiftNative` to arbitrary guest pages.

## Known limits

- This first slice is CPU-first; Android GPU acceleration is deliberately deferred until the basic runtime proves stable across devices.
- Large models will not fit on many phones, especially 32-bit devices. Start with small quantized GGUF coding/instruct models and small context sizes.
- The official Codex ARM64 Linux-musl executable is packaged as-is. CI verifies packaging, but real-device testing is still required to confirm every Codex subprocess assumption behaves correctly under Android's Linux kernel/userspace differences.
- ARMv7 Codex itself remains blocked by upstream release availability. Do not label it supported until we have a reproducible ARMv7 build and device test.

## Source/research notes

The implementation follows current Android W^X requirements: executable native code is APK-bundled instead of copied into writable app storage. Android NDK still lists both `arm64-v8a` and `armeabi-v7a` as supported ABIs. llama.cpp contains Android ARMv7 handling and exposes an OpenAI-compatible HTTP server with model aliases. Codex exposes `--oss`, `--local-provider`, and an OSS base URL override for local providers.
