# RiftRT v2 Native Program Foundation

RiftRT is the RiftOS application execution layer. Android remains the kernel/driver host; RiftKernel owns RiftOS process records, permissions and the RiftFS namespace; RiftDesktop owns native window authority.

## Package and install model

`.rift` remains the portable `rift-app-v1` distribution container in this phase, but importing it means **installing** it. The package is validated and promoted into `C:/Programs/<app-id>` by RiftApps. User state is separate under `D:/Users/Default/AppData/<app-id>`. RiftRT launches the installed copy; it does not execute the selected `.rift` file directly.

A package can include `riftrt.json`:

```json
{
  "engine": "native-webview",
  "entry": "index.html",
  "abi": "riftrt-1",
  "capabilities": ["fs.read", "fs.write", "build.local"],
  "window": { "width": 720, "height": 480 }
}
```

A package with no runtime spec defaults to `native-webview`. A legacy `engine: "iframe"` value is translated to `native-webview`; there is no iframe execution engine anymore.

## Engines

- `native-webview` — V1/default Android-owned application surface. A dedicated Android WebView is mounted directly inside the app's native RiftDesktop WindowRecord. It is not nested inside the trusted shell WebView.
- `worker-js` — compatibility/experimentation Worker runtime using a host-owned canvas and bounded RPC surface.
- `wasm-base64` — sandboxed WebAssembly compatibility runtime using the existing Rift frame/input ABI.
- `rift-vm` — data-only Rift executable VM. A `.rift` installer may carry a `main.rxe` payload using `rift-exec-v1` / `riftvm-1`; the VM validates bounded bytecode and declared host imports before execution.
- `native-arm64` — reserved packaged/plugin direction. Arbitrary downloaded ELF execution from writable storage remains disabled.

## Native application surface

RiftRT first creates the RiftKernel process and native RiftDesktop window. Once Android confirms that WindowRecord exists, RiftRT calls `app.runtime.open`. `RiftNativeAppHost` loads only the installed package for that app id and attaches its dedicated content View through `RiftNativeDesktop.attachContent`.

This removes the previous iframe-in-shell architecture while keeping the renderer replaceable. The first compiled/data-only path now exists as `rift-vm`: Rift++ can target `main.rxe` without changing installation, permissions, drives, taskbar or native window lifecycle. Future native/optimized Rift ABIs can evolve beneath the same boundary.

## Capability ABI

The native app host exposes a frozen bounded `Rift` object covering application lifecycle/info/title, app-local storage, permission requests, RiftFS text/list operations, clipboard/share and the bounded RiftBuild controller surface. There is no arbitrary native-method bridge or shell API.

`build.local` stays honest: planning/controller APIs can exist while `Rift.build.nativeExecutor` remains false until the APK actually ships a trusted compiler/toolchain executor.

For the proven Rift++ Gate 5 path, the `rift-vm` engine recognizes `state.load`, `state.save`, and `state.remove`. These methods require `storage` in both the installed manifest and `riftrt.json`; they do not accept filesystem paths. State is stored under the installed app's private AppData in `riftvm-state.json`, stat-checked as a bounded regular file before read, strictly parsed, bounded to 16 validated non-poison keys, 64 KiB per serialized checkpoint, and 512 KiB total. The internal RiftFS write path uses staged/fsynced atomic replacement. RiftVM itself owns typed checkpoint serialization/validation, so composite VM values still never cross the ordinary host-import boundary as host objects.

## Worker/WASM compatibility

Worker apps retain the host RPC controller and canvas command ABI (`clear`, `rect`, `line`, `text`). WASM packages retain the existing base64 module/import/export path. They are compatibility engines, not the default installation target.

## Desktop integration

RiftRT participates in the existing RiftDesktop window/process model. Android owns focus, z-order, move/resize, minimize/maximize/restore, taskbar and close. Runtime cleanup must converge when closure originates from app code, the native close button, process termination or Android Back.

## Security direction

Installed program paths are fixed by RiftOS. Packages cannot choose a C: install location or remap volumes. Program data stays on D:. The V1 native-webview renderer uses a fixed local origin, CSP, disabled file/content URI access, default-denied external network loading and a fixed WebMessage method set. Future compiled engines must preserve the same containment/permission contracts.
