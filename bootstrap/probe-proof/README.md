# Independent Bootstrap Probe Fixture (source only)

## Manual artifact → signed APK device test (NOT executed yet)

This proof uses a **separate manual** `.github/workflows/build-bootstrap-probe.yml` (`workflow_dispatch`). It compiles `ProbeV1.java` outside the RiftOS APK, using JDK javac, Android SDK D8, and a compile-only `RiftBootstrapEntry` ABI stub which **does not enter the DEX**. The downloadable `riftos-external-probe-v1-dex` artifact contains raw `probe-v1.dex` and its SHA256 sidecar. This separate artifact build never signs/rebuilds RiftOS; the RiftOS APK still uses the existing *user-manual* Builder.

After user-manual signed RiftOS with this source is green/device-installed, open **Admin Approvals** and select **Select external ProbeV1 DEX (Android picker)**. Choose extracted `probe-v1.dex`, tap **Request scoped administrator test**, allow the fixed staging scope and **Stage selected DEX using one-use approval** within 45 seconds. Read the returned SHA-256. Then choose **Select digest-bound probe activation scope**, request a fresh ticket, Allow Once again, and **Activate and start isolated probe once**. Core alone verifies the signer/OS-attested graphical shell PID, SHA-256, approval and foreground lease; the selected file travels via a read-only Binder file descriptor. Execution is only in the predeclared non-exported `:riftBootstrapProbe` Service, NEVER in production Core/Shell. Verify `core status.bootstrapHost.probeProof`, audit, and Core/Shell/RAPP invariants. An activation response means the service was *requested*, not that module execution was proven. No generic module installer exists.

Next negative tests: deny/cancel, expired ticket, replayed ticket, corrupt/mismatched raw DEX, wrong class, induced probe interruption and restored previous/embedded activation. All require deliberate signed-device checks, and **none have been claimed as passed**.

`ProbeV1.java` is a tiny **externally built Java DEX module** implementing the APK-shared `com.riftos.app.RiftBootstrapEntry` contract. It must **never be added to RiftOS's APK Kotlin/Java source set** or copied to APK assets. It is intentionally outside `android/`. This fixture is not compiled, installed, signed, or activated by this checkpoint.

The future canonical external compile-to-DEX flow must use a matching Android SDK and the stable host ABI, then deliver a DEX artifact through a **Core-authorized native user import** rather than RAPP/terminal shortcuts or any retired legacy compiler pipeline. The internal component store stages the DEX under the immutable content-addressed `bootstrap-components/probe-<sha256>.dex` name. Activation is deliberately **proof-only**, via `probe.json` containing:

```json
{
  "schema": "riftos.bootstrap-module/1",
  "api": 1,
  "component": "probe",
  "entrypoint": "com.riftos.bootstrap.proof.ProbeV1",
  "sha256": "<64 lowercase hexadecimal SHA-256 of the actual DEX>"
}
```

An authorized activation requires a fresh explicit user decision, which is **not implemented yet**. The inert nonexported Android `:riftBootstrapProbe` service has to be started by a future trusted Core-controlled proof path. It runs separately from Core and `:riftShell`; it shares the APK UID, which is not separate filesystem privilege isolation.

On successful start the fixture writes an Android AtomicFile `filesDir/bootstrap-components/probe-proof.json` with schema `riftos.bootstrap-probe-proof/1`, revision `probe-v1`, Android process ID and process name. A later device test must clear stale proof evidence before launch, independently attest that PID belongs to `:riftBootstrapProbe`, and compare the new marker with an actual authenticated process/loader event. Mere marker existence is not proof that the desired revision loaded.

Never test critical Core/Shell replacement until probe stage/activate, malformed DEX rejection, crash-interrupted fallback, previous-revision rollback, separate-process survival and retained RAPP behavior pass on a signed user-built APK. Android integration and DEX loading remain unproven here.
