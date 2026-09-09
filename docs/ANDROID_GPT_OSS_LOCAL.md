# RiftOS Android local gpt-oss

The `android-apk` build can run OpenAI's open-weight `gpt-oss-20b` locally on-device through a pinned `llama.cpp` runtime. Inference is performed by a native `llama-server` process bound only to `127.0.0.1`; RiftOS keeps ordinary cleartext networking disabled and grants a cleartext exception only to loopback.

## What is bundled

The APK bundles the `llama.cpp` server executable for `arm64-v8a` and `x86_64`. It does **not** bundle the model weights because the gpt-oss-20b MXFP4 GGUF is roughly 12 GB. The model can be downloaded separately and is discovered from RiftBrowser's app-specific Android Downloads directory or from RiftFS `/models`.

Pinned runtime commit:

- `ggml-org/llama.cpp@304665fe7ac957df95e3ff8c8c4ffdf92dd6ffa3`
- source model: `openai/gpt-oss-20b`
- llama.cpp GGUF distribution: `ggml-org/gpt-oss-20b-GGUF`

The implementation reports a 16 GiB recommended-memory target. Lower-memory phones may fail to load the model or may be terminated by Android under memory pressure.

## RiftOS API

Android exposes the local runtime at `globalThis.RiftLocalAI` and `RiftAndroidAPI.localAI`.

```js
const info = await RiftLocalAI.info();
await RiftLocalAI.downloadModel();
const models = await RiftLocalAI.models();
await RiftLocalAI.start(models[0].id, { contextSize: 2048 });

while (!(await RiftLocalAI.status()).ready) {
  await new Promise(resolve => setTimeout(resolve, 1000));
}

const text = await RiftLocalAI.promptText("Explain what RiftOS is.", {
  maxTokens: 256,
  timeoutMs: 15 * 60 * 1000
});
console.log(text);
```

Long generations are submitted as native jobs and polled from JavaScript, so they are not limited by RiftOS's normal 30-second native-message timeout.

## Build

The Android GitHub Actions workflow installs Android NDK 29, runs `android/scripts/prepare-gpt-oss-runtime.sh`, patches the current native dispatcher with the local-AI routes, then builds/signs the existing APK. The workflow verifies that both native server ABIs are present in the signed artifact.

For a local Linux/macOS Android build, set `ANDROID_NDK_ROOT`, run the two scripts below, then build the APK normally:

```bash
bash android/scripts/prepare-gpt-oss-runtime.sh
python3 android/scripts/patch-local-ai-dispatcher.py
cd android
gradle :app:assembleRelease
```
