# Gate 2 generic module — user-manual device proof

**Signed Android build remains USER-triggered.** The user subsequently authorized the assistant to run bounded RiftOS Local Agent UI tests and RiftShell package/install commands; these are allowed only while available and within the user's request. Do not push RiftOS without explicit permission. Signed #689 (source `72094e4df8b2`) device-proved the Gate 2 positive path and negative tests; new Gate 2-C source remains **UNBUILT/UNPUSHED** until a separate reviewed build.

## Gate 2-C — Controlled recovery + direct Core replay (NEW SOURCE, not yet device-proven)

**Prerequisite:** User manually dispatches signed RiftOS Builder from the eventual reviewed Gate 2-C push, installs it and verifies `core status.moduleHost.activeId=example.alternate-proof`, `proofPresent=true`, `pendingStartup=false`, no extra grants/providers. Do **not** use older #689 to look for new test buttons.

**Direct Core consumed-ticket replay (trusted UI opt-in):**
1. In Admin Approvals, select the existing `example.alternate-proof` manifest. Confirm operation `module.stage` and exact digest target. Request scoped administrator test and select Allow once.
2. Press **Prove Core consumed stage ticket replay**, rather than ordinary Stage. The trusted Shell makes **two** protected Binder calls with the identical bearer, manifest, descriptor content and target. First call must return a valid, staged-but-not-executed receipt. Second call **must** fail inside Core with `Admin ticket absent or used`. UI explicitly prints `Core direct replay PASS` only if those two conditions hold. Any second success or other error is a **failure**.
3. Recheck `core status`: original module active ID/proof nonce unchanged, no pending startup, tickets=0, grants=0, three RAPPs retained.

**Recovery journal simulation (trusted UI opt-in, does not crash Core):**
1. Following the successful replay proof, **Select bounded module recovery proof**. The approved target must be `module://recover/example.alternate-proof/<same SHA-bound manifest revision>`, operation `module.recovery.proof`. Request a new independent consent and Allow once.
2. Press **Prove interrupted startup recovery once**. Core verifies the module is *already* active with a valid historical proof. It writes the **real** previous-active and previous-proof journal, creates the actual pending-startup marker via `activate` for that same module, then runs the **same** `recover` path used after an interrupted startup. There is **no Android process termination**, **no external DEX execution**, **no module-ID switch**.
3. Success must show `Core interrupted startup journal/rollback PASS` and a Core reply `riftos.core.module-recovery-proof/1`, `recovered=true`, `simulatedPendingStartup=true`, `priorProofRestored=true`, and identical previous/restored nonce. The final `core status` must match the exact original active module, proof nonce and PID, `pendingStartup=false`, 0 tickets/grants, Core+Shell and 3 RAPPs intact.
4. **This tests real journal/restore logic but NOT actual power-loss, host-process death or cold-start sequencing.** Keep that stronger proof a separate explicitly planned, bounded device gate.

**Failed SHA admission cleanup regression:**
1. Copy the known-good DEX into a disposable public `D:/Builds/Modules/example.neg-sha-2/` and write a valid manifest with that ID but an intentionally wrong lowercase 64-character SHA, with no capabilities/dependencies.
2. Approve one exact `module.stage` through trusted UI. Core must return `Core module length or digest mismatch` and NOT activate.
3. Core now removes a newly created empty private module ID directory after failed stage and safely prunes legacy empty failed-stage directories before applying the max-32-ID quota. It must **never** remove a directory with staged metadata or the current active module. To prove cleanup thoroughly, use a Core-owned read-only stage inventory/diagnostic in a future gate; public file listing alone does not show private Core storage.
4. Remove only the disposable **public** negative fixture, not Core-private files. Verify known-good `core status` unchanged.

## Positive proof

1. User manually builds and installs signed RiftOS from the new Gate 2 commit. Record run and source SHA. Core and RiftShell must connect; existing RAPPs remain.
2. User packages/updates the external `rift-module-builder` RAPP using existing generic `riftbuild pack-rapp` and `install-rapp` workflow. Its new Build generic module proof button is not present in the original installed version.
3. Launch Module Builder; press **Build generic module proof**, not Build ProbeV1. Confirm registered Kotlin→D8 passes. In RiftOS Files verify `D:/Builds/Modules/example.alternate-proof/module.json` and `module.dex`; manifest entrypoint is `com.riftos.genericproof.AlternateProof`, its SHA comes from the D8 receipt.
4. Open Start → Admin Approvals. Press **Select trusted module manifest from RiftOS Files**; select Independent Alternate Module Proof. Confirm module ID/version/entrypoint, and disclosure of the SAME Android UID.
5. Try **Stage selected generic module once** before authorization: MUST fail. Then **Request scoped administrator test**, verify `module.stage` and `module://stage/<digest>`, tap **Allow once** and stage within 45 seconds. Core must report staged and NOT executed.
6. Press **Select digest-bound generic activation**. Verify independent `module.activate` and `module://activate/example.alternate-proof/<same manifest digest>`. Request NEW scoped approval; **Allow once**; **Activate selected generic module once** within 45 seconds. UI only confirms service-start request.
7. Run `core status`. Require `moduleHost.active=true`, `activeId=example.alternate-proof`, `pendingStartup=false`, `proofPresent=true`. Matching proof has schema `riftos.core.module-proof/1`, module ID/version/SHA/revision, process `com.riftos.app:riftModuleHost`, fresh PID different from Core and Shell. It is a **completed-start proof**, not necessarily a currently live process.
8. Confirm `moduleHost.separateAndroidUid=false`, `runtimeProviderCount=0`, no system admin grants, Core+Shell healthy, installed RAPPs retained and original `bootstrapHost.probeProofPresent` remains unchanged.

## Negative and recovery proof (separately, one at a time)

- **Deny** a fresh stage/activate consent and attempt action: denied.
- **Expire** a fresh approved ticket by waiting >60 sec, then attempt action: Core must reject stale bearer, not stage/activate.
- **Wrong digest** by altering disposable DEX bytes or manifest SHA; staging fails without execution.
- **Invalid manifest/DEX** including path escape, symlinks, unsupported kind/ABI, nonempty requestedCapabilities/dependencies, oversized payload; fail closed.
- **Bad entrypoint**: disposable valid DEX whose manifest names a nonexisting but syntactically valid class; separately stage, approve activation, expect no execution receipt and rollback to previous active pointer. Core/Shell/RAPPs remain healthy. Do not deliberately crash production Core/Shell.
- **Replay** consumed approval; action denied. Native legacy fixed ProbeV1 stage/activate path still available.

**STOP** on unexpected result and send screenshot plus `core status` before proceeding. Generic v1 uses one active module slot; it is for explicitly trusted same-UID code, not a sandbox or separately signed Android runtime-provider APK.
