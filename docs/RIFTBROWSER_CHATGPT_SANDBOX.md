# RiftBrowser ChatGPT sandbox

RiftBrowser can open `https://chatgpt.com` with a persistent Android WebView profile and a private RiftOS filesystem bridge.

## Security boundary

The bridge is installed only for the exact `https://chatgpt.com` origin and only accepts messages from the main frame. Authentication pages such as `auth.openai.com`, Google, Microsoft, and Apple can receive the cookie/popup behavior needed for sign-in, but they never receive the sandbox object.

The sandbox root is app-private storage at `riftfs/browser-sandbox`. Browser code cannot use this bridge to read RiftOS secrets, SAF mounts, arbitrary Android files, or other apps. Path traversal is rejected after canonicalization.

Default folders:

- `workspace/`
- `uploads/`
- `downloads/`

Bridge transfers are capped at 8 MiB per file/message to protect the WebView process on lower-memory phones.

## JavaScript API

When `chatgpt.com` is loaded, RiftBrowser injects `globalThis.RiftSandboxFS`:

```js
await RiftSandboxFS.info();
await RiftSandboxFS.list("workspace", { recursive: true });
await RiftSandboxFS.writeText("workspace/notes.txt", "hello");
const text = await RiftSandboxFS.readText("workspace/notes.txt");
await RiftSandboxFS.writeBase64("workspace/image.png", base64Data);
await RiftSandboxFS.move("workspace/a.txt", "workspace/b.txt", { overwrite: true });
await RiftSandboxFS.remove("workspace/b.txt");
```

Supported operations: stat, list, read/write UTF-8 text, read/write Base64 binary, mkdir, move, and remove.

## Login behavior

RiftBrowser enables third-party cookies only while the main page is in the ChatGPT/OpenAI authentication flow (including common Google, Microsoft, and Apple login hosts) and converts user-initiated auth popups into same-tab navigation. Other sites keep third-party cookies disabled.

Some identity providers can independently reject embedded Android WebViews. If a provider blocks embedded sign-in, that is an upstream provider restriction rather than a RiftSandbox permission issue.

## Important limitation

Exposing `RiftSandboxFS` makes the sandbox available to JavaScript running on `chatgpt.com`; it does not automatically make the ChatGPT model invoke the API as a native tool. A later RiftBrowser integration can add explicit file attachment/tool UI on top of this bridge without widening the Android filesystem boundary.
