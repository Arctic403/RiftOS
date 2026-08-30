# RiftOS

RiftOS is a touch-first operating environment that runs in two modes:

- **Web/PWA mode** on Safari and other modern browsers.
- **RiftOS Native** inside a thin Swift/WKWebView iOS host.

The True OS refactor removes the old duplicated runtime layers. RiftOS now has one kernel API, one filesystem API, one process table, one shell path, and one capability model.

## True OS Core

### RiftKernel
`src/riftcore.js` owns:

- boot state and versioning
- process/PID lifecycle
- application registry
- capability grants
- RiftNative bridge state
- mount table
- system information

### RiftFS
RiftFS prefers **OPFS** for the local filesystem and keeps the original `riftos` IndexedDB store as a compatibility mirror.

The namespace starts with:

```text
/
├── home/
├── apps/
├── system/
└── mounts/
```

When RiftOS runs inside the native iOS host, user-approved Files folders can be mounted below `/mounts` and accessed through the same RiftFS API used by the web runtime.

### RiftShell
RiftShell talks directly to RiftKernel/RiftFS. It includes filesystem, process, mount, storage, capability and Git commands.

### Rift Apps
`.rift` packages are stored under RiftFS `/apps`. Installed apps launch in sandboxed iframes and request declared capabilities through the kernel permission broker.

### RiftGit
RiftGit uses RiftFS directly. GitHub workspaces live under:

```text
/home/repos/<owner>/<repo>
```

Push creates one Git commit containing the complete local change set instead of one commit per changed file.

### RiftDev
RiftDev remains a pinned, read-only clone of `Arctic403/Editor`. The Editor repository itself is never modified by RiftOS. RiftOS adds its integration overlay only to the staged Pages copy.

## RiftOS Native

`native/ios` contains the Swift host scaffold.

The bridge currently supports:

- persistent user-selected Files directory mounts
- directory listing/stat/read/write/create/remove
- document picking
- clipboard
- share sheet
- device information
- notification authorization and local notification scheduling

Native access remains inside normal iOS sandbox and user permission boundaries.

## RiftEngine status

The custom WebKit/WebCore experiment is **on hold**, not deleted.

RiftEngine source, pins and dedicated GitHub Actions workflows remain in the repository, but normal RiftOS Pages deployments no longer install Emscripten or rebuild/publish the heavyweight engine. This keeps ordinary RiftOS/RiftDev iteration fast.

See `riftengine/README.md` for the preserved engine roadmap.

## Deploy

The normal Pages workflow now assembles only the RiftOS shell and the pinned RiftDev clone.

The native iOS validation workflow runs automatically when `native/ios/**` or its workflow changes on `main`, also validates matching pull requests, and can still be started manually with `workflow_dispatch`. It uses the current Xcode 26 toolchain, generates the Xcode project with XcodeGen, builds both the iOS Simulator target and an unsigned physical-iPhone target, and uploads the resulting CI artifacts for seven days.

### TestFlight from GitHub Actions

`.github/workflows/riftos-testflight.yml` is a manual delivery workflow. It signs the physical-device archive with an App Store distribution certificate/profile and uploads the IPA to App Store Connect/TestFlight using an App Store Connect API key. Signing material is decoded only on the ephemeral macOS runner and is removed at the end of the job; the signed IPA itself is not published as a public GitHub artifact.

The workflow expects these GitHub Actions secrets:

- `IOS_DISTRIBUTION_CERTIFICATE_BASE64`
- `IOS_DISTRIBUTION_CERTIFICATE_PASSWORD`
- `IOS_APPSTORE_PROVISIONING_PROFILE_BASE64`
- `APPSTORE_CONNECT_KEY_ID`
- `APPSTORE_CONNECT_ISSUER_ID`
- `APPSTORE_CONNECT_PRIVATE_KEY_BASE64`

The App Store Connect app record and provisioning profile must cover the bundle identifier `com.riftos.native`. The workflow uses its GitHub run number as the unique TestFlight build number.

## Native RiftBrowser and AI workspace

The native iOS shell now includes a multi-tab `WKWebView` RiftBrowser. Native Browser launches no longer depend on Safari, and the default Browser destination is ChatGPT. Browser tabs deliberately do not receive `RiftNative` filesystem privileges.

A dedicated `RiftWorkspace` is created under the app's Documents container and exposed through the iOS Files app. It is also mounted inside RiftFS as `/mounts/RiftWorkspace`.

Trusted RiftOS code receives `window.RiftWorkspace` with native workspace read/write/list/move APIs plus `riftcity-ai-patch` preview/apply/history/rollback support. Patch writes are constrained to the workspace, validate paths and optional base hashes, and preserve rollback snapshots under protected `.rift` metadata.
