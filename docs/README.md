# RiftOS Documentation Map

## Trust policy

Documentation is **UNVERIFIED by default**.

A document becomes trusted only after its owning source has been audited directly and the document contains an explicit `## Verification status` section marked `VERIFIED`. Source code, Gradle packaging and Android manifest state outrank documentation.

Changing relevant source invalidates the affected document's trusted status until it is re-audited.

`docs/SOURCE_OWNERSHIP.md` records documentation ownership. Ownership does **not** mean a source is packaged, live, verified or device-proven.

[`PATCH_HISTORY.md`](PATCH_HISTORY.md) records source-first patch notes. It is descriptive evidence, not an authority substitute for source/tests/builds.

## Current verified set

| System | Status | Documentation | Live/source basis |
| --- | --- | --- | --- |
| RiftOS engine | **VERIFIED** | [`systems/engine/README.md`](systems/engine/README.md) | Native Kotlin composition + Gradle + manifest |
| Boot/startup | **VERIFIED** | [`systems/boot/README.md`](systems/boot/README.md) | `MainActivity`, `RiftMcpRuntime`, Gradle, manifest |
| RiftKernel identity | **VERIFIED** | [`systems/kernel/README.md`](systems/kernel/README.md) | Native protected engine/task model; `riftcore.js` classified retained |
| Runtime capability reporting | **VERIFIED** | [`systems/runtime-capabilities/README.md`](systems/runtime-capabilities/README.md) | Native owner state; `riftruntime.js` classified retained |
| Android host | **VERIFIED** | [`systems/android-host/README.md`](systems/android-host/README.md) | `MainActivity`, manifest, resources and lifecycle/call-site audit |
| Desktop/window manager | **VERIFIED** | [`systems/desktop/README.md`](systems/desktop/README.md) | `RiftNativeDesktop.kt` + callers/listeners/state/geometry audit |
| Shell UI/system windows | **VERIFIED** | [`systems/shell-ui/README.md`](systems/shell-ui/README.md) | `RiftNativeSystemApps.kt` + launcher/desktop/shell lifecycle audit |
| RiftFS | **VERIFIED** | [`systems/riftfs/README.md`](systems/riftfs/README.md) | `RiftVolumePaths.kt` + consumer containment/SAF boundary audit |
| Retired native dispatcher | **VERIFIED** | [`systems/native-dispatcher/README.md`](systems/native-dispatcher/README.md) | removed source + replacement-owner absence audit |
| Transfer ownership | **VERIFIED** | [`systems/transfers/README.md`](systems/transfers/README.md) | Shell/MCP/Git transfer and SAF non-owner audit |
| RiftBrowser | **VERIFIED** | [`systems/browser/README.md`](systems/browser/README.md) | browser coordinator/caller/lifecycle/chooser audit |
| Browser engine | **VERIFIED** | [`systems/browser/engine/README.md`](systems/browser/engine/README.md) | renderer-neutral interface/consumer audit |
| Android WebView backend | **VERIFIED** | [`systems/browser/engine/android-webview/README.md`](systems/browser/engine/android-webview/README.md) | WebView security/auth/inspector/crash-recovery audit |
| Browser MCP compatibility | **VERIFIED** | [`systems/browser/mcp-compat/README.md`](systems/browser/mcp-compat/README.md) | exact-origin/manual-send/protocol-state audit |
| AI site adapters | **VERIFIED** | [`systems/browser/ai-adapters/README.md`](systems/browser/ai-adapters/README.md) | live selector/hostname/manual-send contract audit |
| Rift MCP | **VERIFIED** | [`systems/mcp/README.md`](systems/mcp/README.md) | process-owner/shared-server/transport-boundary audit |
| MCP server | **VERIFIED** | [`systems/mcp/server/README.md`](systems/mcp/server/README.md) | JSON-RPC/idempotency/result-framing audit |
| MCP tool host | **VERIFIED** | [`systems/mcp/tool-host/README.md`](systems/mcp/tool-host/README.md) | 18-tool registry/grants/audit/normalization audit |
| MCP sandbox / Code Mode | **VERIFIED** | [`systems/mcp/sandbox/README.md`](systems/mcp/sandbox/README.md) | workspace containment/transactions/PI-v2 audit |
| MCP relay | **VERIFIED** | [`systems/mcp/relay/README.md`](systems/mcp/relay/README.md) | WSS transport/config/reconnect/authority audit |
| Project exporter | **VERIFIED** | [`systems/mcp/project-exporter/README.md`](systems/mcp/project-exporter/README.md) | deterministic paging/snapshot/filter/cursor audit |
| Workspace | **VERIFIED** | [`systems/workspace/README.md`](systems/workspace/README.md) | canonical-root/writer/watcher/live-vs-retained audit |
| Workspace Records | **VERIFIED** | [`systems/workspace/live/README.md`](systems/workspace/live/README.md) | watcher/persistence/diff/file-identity/provenance/manifest-chain audit |
| Dev Lab | **VERIFIED** | [`systems/dev-lab/README.md`](systems/dev-lab/README.md) | staging/snapshot/publish/recovery audit |
| RiftLLM bridge | **VERIFIED** | [`systems/riftllm-bridge/README.md`](systems/riftllm-bridge/README.md) | fixed Provider/pairing/canary-controller audit |
| Apps/package host | **VERIFIED** | [`systems/apps/README.md`](systems/apps/README.md) | package/origin/capability/lifecycle audit |
| RiftRT / runtime | **VERIFIED** | [`systems/riftrt/README.md`](systems/riftrt/README.md) | legacy-manager inactivity/replacement-owner audit |
| RiftRT engines | **VERIFIED** | [`systems/riftrt/engines/README.md`](systems/riftrt/engines/README.md) | current engine reachability inventory audit |
| Native WebView app engine | **VERIFIED** | [`systems/riftrt/engines/native-webview/README.md`](systems/riftrt/engines/native-webview/README.md) | live installed-program renderer audit |
| Worker JS reference engine | **VERIFIED** | [`systems/riftrt/engines/worker-js/README.md`](systems/riftrt/engines/worker-js/README.md) | retained/inactive reachability audit |
| WASM reference engine | **VERIFIED** | [`systems/riftrt/engines/wasm-base64/README.md`](systems/riftrt/engines/wasm-base64/README.md) | retained/inactive reachability audit |
| Native ARM64 roadmap engine | **VERIFIED** | [`systems/riftrt/engines/native-arm64/README.md`](systems/riftrt/engines/native-arm64/README.md) | retained/unsupported roadmap audit |
| RiftVM engine | **VERIFIED** | [`systems/riftrt/engines/rift-vm/README.md`](systems/riftrt/engines/rift-vm/README.md) | live VM/headless-host/import-boundary audit |
| Rift++ Core | **VERIFIED** | [`systems/riftpp-core/README.md`](systems/riftpp-core/README.md) | 0.9.0 source candidate: Gate 1A frozen + Gate 1B UTF-16 text/numeric audit; device proof pending |
| RiftShell | **VERIFIED** | [`systems/shell/README.md`](systems/shell/README.md) | process/filesystem/transaction/MCP-authority audit |
| Experimental RiftCLI | **VERIFIED** | [`systems/experimental-cli/README.md`](systems/experimental-cli/README.md) | process-local router/V0/IR/tokenizer/freeze audit |
| RiftGit | **VERIFIED** | [`systems/git/README.md`](systems/git/README.md) | native GitHub/metadata/push/pull/rollback audit |
| RiftRepo | **VERIFIED** | [`systems/riftrepo/README.md`](systems/riftrepo/README.md) | verified inactive/retained local-checkpoint design |
| RiftVault | **VERIFIED** | [`systems/riftvault/README.md`](systems/riftvault/README.md) | verified inactive/retained backup design |
| RiftBuild | **VERIFIED** | [`systems/riftbuild/README.md`](systems/riftbuild/README.md) | inactive controller + live non-executing app façade |
| RiftMemory | **VERIFIED** | [`systems/riftmemory/README.md`](systems/riftmemory/README.md) | verified inactive/retained cache design |
| Files | **VERIFIED** | [`systems/files-app/README.md`](systems/files-app/README.md) | native RiftFS/SAF/editor data-safety audit |
| Settings | **VERIFIED** | [`systems/settings/README.md`](systems/settings/README.md) | native Git/RiftLLM credential-ingress audit |
| Preview | **VERIFIED** | [`systems/preview/README.md`](systems/preview/README.md) | workspace-local/per-root Chromium preview audit |
| Diagnostics | **VERIFIED** | [`systems/diagnostics/README.md`](systems/diagnostics/README.md) | bounded browser-renderer crash diagnostics audit |
| Secrets | **VERIFIED** | [`systems/secrets/README.md`](systems/secrets/README.md) | bounded Android-Keystore secret-store audit |
| Relay service | **VERIFIED** | [`systems/relay-service/README.md`](systems/relay-service/README.md) | bounded Worker/DO/Android relay transport audit |
| Build/validation | **VERIFIED** | [`systems/build-validation/README.md`](systems/build-validation/README.md) | exact source/docs/test/Builder packaging-gate audit |
| Vortex local agent | **VERIFIED** | [`systems/vortex-agent/README.md`](systems/vortex-agent/README.md) | fixed-package bounded Accessibility-agent audit |
| Vortex bridge | **VERIFIED** | [`systems/vortex-bridge/README.md`](systems/vortex-bridge/README.md) | bounded fixed Binder/artifact/session audit |
| Chat handoff | **VERIFIED** | [`systems/chat-handoff/README.md`](systems/chat-handoff/README.md) | bounded local bundle/integrity/parser audit |

Every subsystem not listed in the verified set above remains **UNVERIFIED** until its own source-first pass is completed, even if it was edited previously.

## Subsystem audit queue / maintenance index

| System | Trust | Documentation | Primary source area |
| --- | --- | --- | --- |
| Android host | **VERIFIED** | [`systems/android-host/README.md`](systems/android-host/README.md) | `MainActivity.kt`, manifest, resources + lifecycle/call-site audit |
| Shell UI/system windows | **VERIFIED** | [`systems/shell-ui/README.md`](systems/shell-ui/README.md) | `RiftNativeSystemApps.kt` + launcher/desktop/shell lifecycle audit |
| RiftFS | **VERIFIED** | [`systems/riftfs/README.md`](systems/riftfs/README.md) | `RiftVolumePaths.kt` + consumer containment/SAF boundary audit |
| Retired native dispatcher | **VERIFIED** | [`systems/native-dispatcher/README.md`](systems/native-dispatcher/README.md) | removed source + replacement-owner absence audit |
| Transfer ownership | **VERIFIED** | [`systems/transfers/README.md`](systems/transfers/README.md) | Shell/MCP/Git transfer and SAF non-owner audit |
| Desktop/window manager | **VERIFIED** | [`systems/desktop/README.md`](systems/desktop/README.md) | `RiftNativeDesktop.kt` + callers/listeners/state/geometry audit |
| RiftBrowser | **VERIFIED** | [`systems/browser/README.md`](systems/browser/README.md) | browser coordinator/caller/lifecycle/chooser audit |
| Browser engine | **VERIFIED** | [`systems/browser/engine/README.md`](systems/browser/engine/README.md) | renderer-neutral interface/consumer audit |
| Android WebView backend | **VERIFIED** | [`systems/browser/engine/android-webview/README.md`](systems/browser/engine/android-webview/README.md) | WebView security/auth/inspector/crash-recovery audit |
| Browser MCP compatibility | **VERIFIED** | [`systems/browser/mcp-compat/README.md`](systems/browser/mcp-compat/README.md) | exact-origin/manual-send/protocol-state audit |
| AI site adapters | **VERIFIED** | [`systems/browser/ai-adapters/README.md`](systems/browser/ai-adapters/README.md) | live selector/hostname/manual-send contract audit |
| Rift MCP | **VERIFIED** | [`systems/mcp/README.md`](systems/mcp/README.md) | process-owner/shared-server/transport-boundary audit |
| MCP server | **VERIFIED** | [`systems/mcp/server/README.md`](systems/mcp/server/README.md) | JSON-RPC/idempotency/result-framing audit |
| MCP tool host | **VERIFIED** | [`systems/mcp/tool-host/README.md`](systems/mcp/tool-host/README.md) | 18-tool registry/grants/audit/normalization audit |
| MCP sandbox / Code Mode | **VERIFIED** | [`systems/mcp/sandbox/README.md`](systems/mcp/sandbox/README.md) | workspace containment/transactions/PI-v2 audit |
| MCP relay | **VERIFIED** | [`systems/mcp/relay/README.md`](systems/mcp/relay/README.md) | WSS transport/config/reconnect/authority audit |
| Project exporter | **VERIFIED** | [`systems/mcp/project-exporter/README.md`](systems/mcp/project-exporter/README.md) | deterministic paging/snapshot/filter/cursor audit |
| Workspace | **VERIFIED** | [`systems/workspace/README.md`](systems/workspace/README.md) | canonical-root/writer/watcher/live-vs-retained audit |
| Workspace Records | **VERIFIED** | [`systems/workspace/live/README.md`](systems/workspace/live/README.md) | watcher/persistence/diff/file-identity/provenance/manifest-chain audit |
| Dev Lab | **VERIFIED** | [`systems/dev-lab/README.md`](systems/dev-lab/README.md) | staging/snapshot/publish/recovery audit |
| RiftLLM bridge | **VERIFIED** | [`systems/riftllm-bridge/README.md`](systems/riftllm-bridge/README.md) | fixed Provider/pairing/canary-controller audit |
| Apps/package host | **VERIFIED** | [`systems/apps/README.md`](systems/apps/README.md) | package/origin/capability/lifecycle audit |
| RiftRT / runtime | **VERIFIED** | [`systems/riftrt/README.md`](systems/riftrt/README.md) | legacy-manager inactivity/replacement-owner audit |
| RiftRT engines | **VERIFIED** | [`systems/riftrt/engines/README.md`](systems/riftrt/engines/README.md) | current engine reachability inventory audit |
| Native WebView app engine | **VERIFIED** | [`systems/riftrt/engines/native-webview/README.md`](systems/riftrt/engines/native-webview/README.md) | live installed-program renderer audit |
| Worker JS reference engine | **VERIFIED** | [`systems/riftrt/engines/worker-js/README.md`](systems/riftrt/engines/worker-js/README.md) | retained/inactive reachability audit |
| WASM reference engine | **VERIFIED** | [`systems/riftrt/engines/wasm-base64/README.md`](systems/riftrt/engines/wasm-base64/README.md) | retained/inactive reachability audit |
| Native ARM64 roadmap engine | **VERIFIED** | [`systems/riftrt/engines/native-arm64/README.md`](systems/riftrt/engines/native-arm64/README.md) | retained/unsupported roadmap audit |
| RiftVM engine | **VERIFIED** | [`systems/riftrt/engines/rift-vm/README.md`](systems/riftrt/engines/rift-vm/README.md) | live VM/headless-host/import-boundary audit |
| Rift++ Core | **VERIFIED** | [`systems/riftpp-core/README.md`](systems/riftpp-core/README.md) | 0.9.0 source candidate: Gate 1A frozen + Gate 1B UTF-16 text/numeric audit; device proof pending |
| RiftShell | **VERIFIED** | [`systems/shell/README.md`](systems/shell/README.md) | process/filesystem/transaction/MCP-authority audit |
| Experimental RiftCLI | **VERIFIED** | [`systems/experimental-cli/README.md`](systems/experimental-cli/README.md) | process-local router/V0/IR/tokenizer/freeze audit |
| RiftGit | **VERIFIED** | [`systems/git/README.md`](systems/git/README.md) | native GitHub/metadata/push/pull/rollback audit |
| RiftRepo | **VERIFIED** | [`systems/riftrepo/README.md`](systems/riftrepo/README.md) | verified inactive/retained local-checkpoint design |
| RiftVault | **VERIFIED** | [`systems/riftvault/README.md`](systems/riftvault/README.md) | verified inactive/retained backup design |
| RiftBuild | **VERIFIED** | [`systems/riftbuild/README.md`](systems/riftbuild/README.md) | inactive controller + live non-executing app façade |
| RiftMemory | **VERIFIED** | [`systems/riftmemory/README.md`](systems/riftmemory/README.md) | verified inactive/retained cache design |
| Files | **VERIFIED** | [`systems/files-app/README.md`](systems/files-app/README.md) | native RiftFS/SAF/editor data-safety audit |
| Settings | **VERIFIED** | [`systems/settings/README.md`](systems/settings/README.md) | native Git/RiftLLM credential-ingress audit |
| Preview | **VERIFIED** | [`systems/preview/README.md`](systems/preview/README.md) | workspace-local/per-root Chromium preview audit |
| Diagnostics | **VERIFIED** | [`systems/diagnostics/README.md`](systems/diagnostics/README.md) | bounded browser-renderer crash diagnostics audit |
| Secrets | **VERIFIED** | [`systems/secrets/README.md`](systems/secrets/README.md) | bounded Android-Keystore secret-store audit |
| Relay service | **VERIFIED** | [`systems/relay-service/README.md`](systems/relay-service/README.md) | bounded Worker/DO/Android relay transport audit |
| Build/validation | **VERIFIED** | [`systems/build-validation/README.md`](systems/build-validation/README.md) | exact source/docs/test/Builder packaging-gate audit |
| Vortex local agent | **VERIFIED** | [`systems/vortex-agent/README.md`](systems/vortex-agent/README.md) | fixed-package bounded Accessibility-agent audit |
| Vortex bridge | **VERIFIED** | [`systems/vortex-bridge/README.md`](systems/vortex-bridge/README.md) | bounded fixed Binder/artifact/session audit |
| Chat handoff | **VERIFIED** | [`systems/chat-handoff/README.md`](systems/chat-handoff/README.md) | bounded local bundle/integrity/parser audit |

## Documentation contract

A verified subsystem README must be reconstructed from current source and include:
- source ownership;
- live versus retained/inactive code status;
- runtime/data flow;
- explicit non-ownership boundaries;
- invariants;
- failure signatures;
- fix map;
- validation requirements;
- verification status.

Do not copy historical claims forward merely because they appear in another README.

## Cross-cutting documents

Cross-cutting files such as [`TRUE_OS_ARCHITECTURE.md`](TRUE_OS_ARCHITECTURE.md), [`ANDROID_NATIVE_ARCHITECTURE.md`](ANDROID_NATIVE_ARCHITECTURE.md), `RIFTBROWSER_ARCHITECTURE.md`, `RIFT_MCP_APP_ARCHITECTURE.md`, `RIFTWORKSPACE_WEB_ARCHITECTURE.md`, `RIFTRT-v1.md`, [`PUBLIC_SURFACES.md`](PUBLIC_SURFACES.md) and [`PROJECT_STATUS.md`](PROJECT_STATUS.md) must also be audited against source before being trusted for a subsystem.

Operational references: [`../LOCAL_MCP_MODE.md`](../LOCAL_MCP_MODE.md), [`../ROADMAP.md`](../ROADMAP.md), and [`RIFT_RAW_CHAT_PROTOCOL.md`](RIFT_RAW_CHAT_PROTOCOL.md).

## Debugging rule

Start from source ownership, not prose. A document without verified status is a lead for where to inspect, not evidence that the behavior exists.
