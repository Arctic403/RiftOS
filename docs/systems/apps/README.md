# RiftOS Programs and Package Host

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Purpose

The current Apps subsystem discovers and runs Rift program packages that already exist in app-private RiftFS.

It is an **installed-package host**, not a package installer.

There is currently no source-proven native install/update/uninstall transaction for `.rift` packages.

## Source ownership

Live:
- `MainActivity.kt` — launcher discovery of package candidates under C:/Programs.
- `RiftBrowserAppHost.kt` — package validation, WebView execution, per-app origin, capability bridge, grants, app storage/filesystem policy.
- `RiftNativeShell.kt` — installed-app listing and persisted-grant inspect/revoke.

Retained/non-live:
- `src/riftapps.js`
- `src/riftapps-files.js`
- `src/riftrt.js`

Those retained files are not packaged as the current Android package manager.

## Package location

Primary installed package:

`/C:/Programs/<app-id>/package.json`

The host also contains a legacy execution fallback:

`/apps/packages/<app-id>/package.json`

The native launcher discovers only C:/Programs.

Launcher now requires the package directory name to exactly equal the manifest id.

Execution performs stronger validation again when opening.

## Package format

`package.json` is a bounded JSON document:
- 1 byte .. 8 MiB;
- `manifest` object required;
- manifest id must equal requested app id;
- `files` object required;
- entry defaults to `index.html`;
- entry path is normalized and may not contain traversal/NUL;
- entry must exist as a string in `files`.

App id:
`^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$`

## Capability catalog

The current implemented grantable capabilities are exactly:

- fs.read
- fs.write
- network
- clipboard.read
- clipboard.write
- share
- build.local

Packages declaring another capability now fail package validation.

Previously listed but unimplemented notification/repair/native capability names were removed during this audit rather than left as fake permissions.

## Permission model

A capability operation is accepted only when:
1. capability is in the implemented host allowlist;
2. package declared it;
3. the user has previously granted it or approves the native AlertDialog.

Grant records live in private `rift-native` preferences under:

`setting:permissions:<app-id>`

A permission dialog checks that the app instance is still live before persisting a grant or running the operation.

This prevents a permission prompt left open after app closure from granting a dead instance.

## Grant inspection/revocation

Native RiftShell:

`permissions`
or
`permissions list`

reports persisted installed-app grants.

This audit added:

`permissions revoke <app-id> [capability|all]`

to remove one grant or the complete app grant record.

Revoke validates the app id and capability token before changing private preferences.

When `network` or `all` is revoked while the app is already running, RiftShell forwards that revocation through the active MainActivity to `RiftBrowserAppHost.onGrantRevoked()`, which immediately restores `blockNetworkLoads=true` for every matching live app instance. Other capabilities are checked on each bridge request, so removing them from preferences takes effect on the next call.

## Renderer ownership

Each installed app runs in its own Android WebView attached directly to one RiftDesktop window.

It is not:
- an iframe;
- shell-rendered;
- part of RiftBrowser's browsing tab set.

Chromium ownership remains explicitly inside the RiftBrowser-named host source.

## Per-app origin isolation

Installed apps previously shared one logical origin, `https://app.riftos.local`.

That allowed same-origin cookie state to be shared across otherwise separate app WebViews.

This audit replaced it with a deterministic per-app origin:

`https://app-<first-128-bits-of-SHA256(app-id)>.riftos.local`

The exact origin is used for:
- base URL;
- local asset interception;
- WebMessage bridge allowlist;
- source-origin checks;
- CSP local sources.

Third-party cookies are explicitly disabled for installed app WebViews.

## Local asset serving

Requests are served from the package `files` JSON only when:
- HTTPS;
- host equals this app's derived origin;
- first URL path segment decodes to this app id;
- asset path is normalized without traversal/NUL;
- requested entry exists as a string.

Other local paths return 404.

## WebView security

Installed app WebView:
- JavaScript enabled;
- DOM storage disabled;
- file access disabled;
- content access disabled;
- automatic JS window creation disabled;
- multiple windows disabled;
- mixed content blocked;
- no cache;
- third-party cookies disabled.

Top-level navigation is allowed only to the app's own local HTTPS origin.

External top-level navigation is blocked.

## Network capability

Network has two separate gates.

### Declaration / CSP preparation

If the package declares `network`, its CSP permits external network/resource categories that are intended to work after a grant.

External **scripts are never enabled by network permission**. Script sources remain:
- inline packaged/bootstrap script;
- this app's own local origin;
- blob.

That prevents remote code from becoming an implicit holder of already-granted native capabilities.

CSP also blocks:
- frames;
- objects;
- forms;
- frame ancestors.

### Runtime grant

Actual WebView network loads start blocked unless:
- package declared `network`;
- a saved user grant already exists.

Approving the native network permission flips `blockNetworkLoads=false` for that live app instance.

## Native messaging

Bridge name:
`RiftNativeApp`

Requires Android WebMessage listener support.

Messages:
- exact per-app HTTPS origin only;
- main frame only;
- <=1 MiB UTF-8;
- JSON object.

There is no arbitrary method/native dispatcher.

Unsupported methods return an error.

## Exposed app API

Ungated own-app operations:
- app.ready
- app.close
- app.setTitle
- app.info
- storage.get/set/remove

Capability-gated:
- fs.readText/list
- fs.writeText
- clipboard.read/write
- share
- build.doctor/plan/submit/runs/artifacts

`build.local` currently exposes planning/diagnostic shape only:
- nativeExecutor=false;
- submit fails unsupported;
- runs returns empty;
- artifacts lists D:/Builds.

This does not claim a live local compiler executor.

## Filesystem policy

With `fs.read`, app may read:
- its own C:/Programs/<app-id>;
- its own AppData;
- approved public D: roots.

With `fs.write`, app may write:
- its own AppData;
- approved public D: user/project data roots.

It may **not write its installed C:/Programs package** through the app API.

Approved D: roots:
- Workspace
- Projects
- Packages
- Builds
- Documents
- Downloads
- Temp

All paths pass RiftVolumePaths normalization plus canonical RiftFS containment.

Text read/write bound: 8 MiB.

App text writes now use temp + backup + rename atomic replacement.

## App storage

Own JSON storage:
`/D:/Users/Default/AppData/<app-id>/storage.json`

Maximum encoded storage: 1 MiB.

Storage writes now use the same atomic replacement helper.

## Bounded UI/data surfaces

- filesystem list: <=5000 entries;
- clipboard read: <=64000 characters;
- clipboard write: <=64000 characters;
- share text: <=256000 characters;
- app title: <=96 characters;
- storage key: <=160 characters;
- incoming bridge message: <=1 MiB.

## Desktop lifecycle

The previous host resumed all installed-app WebViews whenever MainActivity resumed, even when a Desktop window was minimized.

This audit introduced managed app WebViews whose renderer lifecycle follows actual native View state:
- Activity paused -> all paused;
- detached/hidden/minimized -> paused;
- attached + shown + Activity resumed -> resumed.

Desktop remains sole owner of outer window visibility/geometry.

## Renderer loss

Installed-app renderer death:
- records privacy-limited crash metadata;
- removes instance from host;
- detaches native content;
- removes WebMessage listener;
- destroys dead WebView;
- closes the Desktop window.

It does not kill native RiftOS.

## Close/destroy

Normal close removes instance, detaches content, removes listener, blanks/destroys WebView.

Host destroy closes all instances and shuts down its background executor.

Replies from background operations are delivered only while the same instance remains registered.

## No installer

There is no current native:
- install;
- update;
- uninstall;
- package signing/trust transaction.

Therefore old install/update/rollback behavior in `src/riftapps.js` is retained design/reference only.

Packages must already be present in C:/Programs for launcher discovery.

## Source fixes in this audit

- launcher requires folder name == manifest id;
- unsupported/unimplemented declared capabilities now fail validation;
- removed fake unimplemented capability names from grant allowlist;
- remote scripts no longer become allowed through network declaration;
- added form/frame CSP restrictions;
- permission prompt refuses to grant a closed app instance;
- app filesystem/storage writes made atomic;
- installed-app renderers now pause when minimized/hidden;
- list/clipboard/share payloads bounded;
- added native grant revocation;
- replaced shared app origin with deterministic per-app origins;
- disabled third-party cookies.

## Critical invariants

- no native installer is claimed;
- execution revalidates package;
- each app has distinct origin;
- native bridge is main-frame/exact-origin/1 MiB bounded;
- package cannot declare unknown host capability;
- capability requires declaration + user grant;
- grant can be revoked;
- C:/Programs is read-only through app fs API;
- app renderer pauses when hidden;
- remote network access never broadens script authority;
- no raw shell/native dispatcher.

## Failure signatures

- two apps share origin/cookie scope -> isolation regression;
- minimized installed app remains resumed -> lifecycle regression;
- remote HTTPS script can run after network grant -> CSP regression;
- package declares unknown capability but launches -> manifest validation regression;
- closed app permission dialog can persist grant -> grant-lifecycle regression;
- fs.write modifies C:/Programs -> filesystem policy regression;
- package writes truncate target on failed replacement -> atomic-write regression;
- no way to revoke persisted grant -> permission-control regression;
- docs claim install/update/uninstall live -> stale package-manager claim.

## Fix map

Launcher discovery -> `MainActivity.kt`.

Execution/origin/CSP/capabilities/app fs/storage/lifecycle -> `RiftBrowserAppHost.kt`.

Grant inspection/revoke -> `RiftNativeShell.kt`.

Future installer -> no current owner; must be separately designed/audited.

## Validation

Second source audit must recheck:
- launcher directory/id rule;
- package schema/id/entry/path bounds;
- exactly seven implemented capability names;
- per-app origin derivation/use;
- third-party-cookie policy;
- CSP script/network separation;
- exact-origin/main-frame message bridge;
- 1 MiB inbound bound;
- fs read/write roots;
- 8 MiB text bound and atomic writes;
- 1 MiB own storage;
- 5000 list / clipboard/share bounds;
- permission instance check and revoke command;
- renderer minimize/resume and crash cleanup;
- absence of native installer/uninstaller.

Installed-device proof should launch at least two packages, test cookie/origin isolation, grant/revoke, minimize/background behavior, renderer death and filesystem boundaries.
