# RiftOS — Android platform host (source ownership)

RiftOS owns the **generic Android platform**: external component loading, binder/IPC transportation, RiftFS, shell/desktop hosting, app and capability isolation, Recovery, installation, and host validation. It does **not** own the source of external languages, developer apps, cloud workers, language proofs, or Core/Shell component builder projects.

## Layout

- `android/` — signed Android host and native generic platform services; **currently includes the still-live embedded graphical Shell**.
- `docs/` — host/platform architecture, active contracts, recovery and security boundaries.
- `.riftgit.json` — local RiftGit metadata (no GitHub project import implied).
- `.gitignore` — build exclusion.

**Moved out of this repo (2026-10-12 ownership cleanup):**

- `/D:/Workspace/RiftComponents` — independent external Core v0.6.1–0.6.5 and graphical Shell DEX source, independent Core/Shell Builder RAPP, recipes and evidence.
- `/D:/Workspace/RiftMcpRelay` — standalone Cloudflare Worker relay.
- `/D:/Workspace/RiftOS-LegacyWeb` — retired browser/JavaScript prototype, old tests and original historical docs; **not built into the Android APK**.
- `/D:/Workspace/rift++` — Rift++ legacy example sources and Rift++ architecture documentation.
- `/D:/Workspace/RiftLLM` — RiftLLM bridge design documents.
- `/D:/Workspace/Semnexis` — Semnexis fixture and bootstrap documentation.
- `/D:/Workspace/Vortex3d` — Vortex integration documentation.
- `/D:/Workspace/RiftBuild-Hosted` — independent hosted build provider documentation.

Old probe files, empty proof folders, retired bootstrap CI and retired RiftCLI documents were **deleted**. Build artifacts live under `/D:/Builds`, not in this source repo.

## Running device vs unbuilt source

As last independently measured on October 12, 2026: installed signed RiftOS **#742** with **independent external Core v0.6.4**, verified Recovery backup v0.6.3 (restore enabled), and **embedded graphical Shell**. Independent Core v0.6.5 / binary folder Binder transfer is **source only** until the user compiles/signs/installs an updated host and manually selects the externally-built Core through Recovery.

Never auto-promote, overwrite the verified Recovery backup, or silently replace the embedded Shell.

## Strict future ownership rule

1. **Generic reusable platform capability**: RiftOS host or a generic Core plugin contract.
2. **External Core/Shell implementation**: RiftComponents, not the Android host.
3. **App/provider/editor/toolchain/compiler/LLM project**: its own workspace/RAPP.
4. **Test/proof tied to a particular project**: its project repo, not RiftOS.
5. **Retired prototype or proof**: remove it or archive outside the live RiftOS tree.
6. **RiftOS-specific existing couplings** in the Android host (for example, historic Codynex connection UI, RiftLLM developer bridge, Vortex client/agent and Rift++ RAPP adapters) must be replaced by generic transport before deleting the live classes. Do not break an existing installed workflow just to pass a directory audit.

Host version and runtime qualification must come from a real signed APK/Recovery/device check, not source inspection.
