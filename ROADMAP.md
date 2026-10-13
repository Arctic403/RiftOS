# RiftOS — platform-only roadmap (2026-10-12)

## Current live architecture

- Signed Android host RiftOS #742.
- Independent external Core v0.6.4 installed and ready; verified v0.6.3 Recovery restore available.
- Embedded graphical Shell remains active.
- Generic platform owns RiftFS, native windows, IPC, Android security boundary, external Core loading and Recovery.

## Ownership cleanup (current)

Independent component source/Builder moved to `/D:/Workspace/RiftComponents`; Cloudflare relay to `/D:/Workspace/RiftMcpRelay`; browser-era source/tests/history to `/D:/Workspace/RiftOS-LegacyWeb`; language/cloud docs to their owners. Obsolete proofs and CLI documentation removed. The Android host's remaining project-specific dependencies are **not yet fully decoupled**: Codynex-specific App Connections UI and RiftLLM Dev API integration require a generic replacement contract before deletion. Preserve behavior until that gate passes.

## Next safe gates

1. Verify Gradle source/manifest host check on the slimmed tree and sign/install host independently. No automatic Core/Recovery changes.
2. Genericize the remaining project-branded Android host surfaces. Move actual project semantics into provider RAPPs/owning repos; host should expose only reusable transport. Retain signers, consent, bounded paths and rollback.
3. Validate external Core v0.6.5 folder transfer with real signed Android host ABI2, manually built component DEX and manual Recovery selection; preserve current external v0.6.4 and v0.6.3 fallback. The Core/Shell builder recipe is in RiftComponents and packaged as a separate RAPP.
4. Later qualify an external graphical Shell. Remove embedded Shell only when feature parity, cold boot and Recovery proof are device-confirmed.
5. Keep old JavaScript prototype, project compiler sources, integration proofs, Cloudflare relay and ad hoc RAPP projects outside the platform repo permanently.

**Never assume directory cleanup alone proves Android build parity or external component boot.**
