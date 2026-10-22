## 2026-10-21 hardening addendum — source only, Builder pending

For requests using `jvmInputs`, managed compiler classpaths now require an **exact set** of the selected Android SDK, exactly one Kotlin stdlib, pinned program JARs, and pinned compiler-only classpath JARs. Undeclared, duplicated, or missing entries are rejected at the host even if the file resides inside the project. Requests using the legacy manifest-only dependency path without `jvmInputs` retain the previous behavior.

To preserve mixed-manifest compatibility, Hosted v0.2.7 now maps explicitly pinned legacy `dependencyJars` to the new `jvmInputs.classpathJars` field when both forms are present, rejecting hash conflicts or more than the host's 16-JAR combined limit; no extra runtime program classes are inferred.

Validated DEX output is now published through a recoverable directory swap: move the prior output to a same-parent backup, rename the staged verified output into place, and restore the prior output if the second move fails. If restoration itself fails, the prior output remains under a uniquely named backup and the failure reports that location; the caller's staging cleanup never deletes that backup. This protects against ordinary rename failure, not power loss between renames.

Eight additional JVM unit tests cover exact classpaths, publication success, rename failures, rollback, and preservation when rollback fails. Existing 12 archive tests remain. **These 20 JUnit tests have NOT been executed through Gradle/Builder.** Hosted source release tests: 14/14 legacy + 33/33 JVM + smoke PASS. The v0.2.7 RAPP is still unbuilt/uninstalled. User controls any RiftOS host push, Builder, APK/RAPP installation.

---

# Generic JVM dependency inputs — source contract 2026-10-21

Status: source implementation complete; not built, installed or device qualified. Existing build.local ABI1 and toolchain-status/2 remain additive/backward compatible.

Kotlin rift-kotlin.json may declare optional jvmInputs:
- programJars: project-relative {source,sha256} JARs compiled into the same D8 invocation as app classes;
- classpathJars: pinned JARs for compile/desugaring resolution only;
- stdlib: optional pinned project JAR replacing (never augmenting) the bundled standard library;
- minCompileSdk: required Android compile API (26..36);
- kotlinStdlibVersion: optional exact base version requirement.
Legacy dependencyJars remains compiler-classpath-only. Hosts advertise jvmDependencyInputsVersion=1 and verified Android API/stdlib metadata; Hosted fails clearly on old hosts for new manifests.

Host revalidates hashes/confinement before compilation and D8, normalizes program JARs into bounded class-only staging (skips multi-release classes/module descriptors), rejects duplicate program class paths and uses D8 for final type validation. One stdlib enters the program exactly once.
Non-class Java resources are staged and hash-receipted; services merge deterministically, conflicting ordinary resources fail, Android-reserved paths cannot be replaced. No AAR resource/manifest merger or Maven resolver is added.
D8 publishes only after successful DEX validation; intermediate files are discarded on failure/cancellation. App class/output overlap and mutable source paths inside output are rejected. Existing Core grant routing passes the request unchanged; no Core/Shell update required.

Hosted passes jvmInputs through compiler and D8 requests, verifies every pinned compile input, selects one stdlib, includes metadata in compile identity, and disables DEX reuse for this path until resource-aware cache support exists. It stages host-returned Java resources, preflights only declared resource paths and preserves manual Compile -> Preflight -> Pack -> Sign -> Verify.
The new program-JAR path cannot be mixed with prebuilt dependencyDex, avoiding an unverified duplicate-DEX combination.

Host Gradle already bundles Android36 and stdlib2.4.10. Generate SDK/stdlib hash metadata as part of that task; runtime checks packaged identities. No builder workflow or compiler pin changes are required. Provider fallback Gradle configuration will target compileSdk36 with a compatible AGP. Both ABIs remain project recipes, outside language syntax.

Validation: fresh live Hosted source passed legacy dependency 14/14, JVM dependency 30/30 and boot/state/compile-gate smoke. Twelve host JVM archive JUnit tests are authored and release-wired but have not run. Kotlin/Gradle build, signed host APK, Hosted v0.2.7 package/install, provider build and device behavior remain manual gates.