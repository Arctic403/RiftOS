# RiftFS ProbeV1 import — user-manual device proof

Status: SOURCE ONLY. No APK compilation, installation, live-device testing or CI claim. The user runs the signed RiftOS Builder manually.

## Preconditions

- Build and install a RiftOS APK from the newly pushed source (not existing #684, source `dfb2bf0`). Report the signed build number/run and commit SHA.
- Keep previously installed RAPPs. In RiftOS Files confirm `/D:/Builds/Modules/probe-v1.dex` is present and size 2,563,188 bytes, or rerun the independent Module Builder RAPP if it is missing. Prior on-device D8 receipt SHA-256: `2d2373dfd13f0f7b9ab15a4146cfef8760a3c7d5aa0f6fa858b7d7feb461877d`.
- Do NOT use an arbitrary/untrusted DEX. Probe execution is in a separate nonexported Android process but under the SAME APP UID and permissions, not a security sandbox.

## Positive staging — distinct one-use approval

1. Open **Start → Admin Approvals** in RiftShell foreground.
2. Tap **Select ProbeV1 DEX from RiftOS Files**. A native dialog lists valid DEX files only under `/D:/Builds/Modules`. Select `probe-v1.dex`. If missing or rejected, capture the error and stop.
3. First tap **Stage selected DEX using one-use approval** without a ticket. Expect a denial: **No stage approval ticket**; no staged/activated change.
4. Tap **Request scoped administrator test**. Check operation `bootstrap.probe.stage`, target `bootstrap://probe/ProbeV1`, and 45-second timeout. **Allow once**.
5. Within 45 seconds tap **Stage selected DEX using one-use approval**. Expect **Core staged immutable external DEX SHA-256 ... No execution**. Record staged SHA; compare with expected prior build receipt if the DEX hasn't changed.

## Positive activation — NEW one-use approval

6. Tap **Select digest-bound probe activation scope**. It should display `bootstrap.probe.activate` bound to the SHA from step 5.
7. Tap **Request scoped administrator test**, review exact SHA, **Allow once**, then **Activate and start isolated probe once** within 45 seconds.
8. In RiftShell run `core status`. Require `bootstrapHost.probeActivationPresent=true`, `probeProofPresent=true` and a fresh `probeProof` with `riftos.bootstrap-probe-proof/1`, revision `probe-v1`, and a PID distinct from the Core/Shell PIDs. Confirm existing RAPPs and Core/Shell survive. If activation is shown but process proof is absent, mark FAIL / INCOMPLETE.

## Negative/regression tests — one at a time

- Deny a new stage request, then try Stage. Denied; no effect.
- Approve a new stage request, wait *more than 45 seconds*, then try Stage. Expired; no effect.
- Attempt stage after the successful ticket has been consumed. Replay must fail.
- Missing module folder, symlink, invalid filename, empty DEX, and file larger than 32 MiB must not stage. Test only using disposable files you create, never production assets.
- Confirm Android SAF picker fallback still exists; general `runtime.register` and `software.install` stay disabled.
- `core status` before/after: no new runtime providers, no elevated grants, no pending journal, Core/Shell healthy.

Provide screenshots of each stage, any failure text, and `core status`. The new RAPP test does NOT promote unrestricted external modules or C2-B2 runtime-provider registration.
