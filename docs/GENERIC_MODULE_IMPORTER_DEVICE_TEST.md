# Gate 2 generic module — user-manual device proof

**USER-OWNED DEVICE TESTS ONLY.** The assistant does not trigger signed APK builds, pack/install RAPPs, use Local Agent, or perform mobile UI tests. Gate 2 source must first be reviewed and explicitly pushed with permission. Current signed #685 is Gate 1 only; it cannot run Gate 2 tests.

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
