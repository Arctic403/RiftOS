# RiftBuild — Retained Local Build Controller Design

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Classification

The JavaScript RiftBuild controller in src/riftbuild.js is retained/inactive.

Current Gradle does not package it and native RiftShell exposes no `rift build` command.

## Source ownership

Retained controller source:
- `src/riftbuild.js`

Separate live non-executing installed-app façade:
- `RiftBrowserAppHost.kt`

Build/Builder execution authority is documented separately under `../build-validation/README.md` and is not owned by retained RiftBuild.

## Retained design

The retained controller defines:
- /system/riftbuild/v1;
- doctor;
- plan;
- build/run records;
- artifact listing;
- build job submission;
- RiftMemory cache helpers.

It only calls `core.native.call("build.execute", ...)` after doctor proves a capability named `localBuildExecutor`.

Its own doctor explicitly fails closed when that capability is absent.

That design is retained reference code, not current APK build execution.

## Separate live installed-app façade

RiftBrowserAppHost exposes a distinct capability-gated installed-app API under `build.local`:
- build.doctor -> reports available=true but ready=false/nativeExecutor=false;
- build.plan -> returns a non-executing plan shape;
- build.submit -> throws because no local RiftBuild executor is installed;
- build.runs -> empty array;
- build.artifacts -> lists /D:/Builds.

This live façade is **not** execution of src/riftbuild.js.

It does not make the retained RiftBuild controller active.

## Activation proof

Current source proves:
- zero native `rift build` shell command;
- zero Gradle include for src/riftbuild.js;
- no wildcard src/** packaging;
- retained globalThis.RiftBuild exists only in JS reference family;
- RiftBrowserAppHost explicitly reports no local compiler executor;
- legacy generic RiftLocalPlatform shell wrapper is retired.

## Retained dependencies

src/riftbuild.js depends on retained:
- RiftOSCore;
- RiftRepo;
- RiftVault;
- RiftMemory.

That dependency graph is another reason it cannot be treated as independently live.

## Trust rule

VERIFIED status for this README means the current **inactive controller + live non-executing façade split** is verified.

It does not certify local Java/Gradle/SDK/NDK execution.

## Critical invariants

- src/riftbuild.js remains unpackaged;
- build.submit continues to fail closed while no native executor exists;
- build.doctor/plan must not imply compilation occurred;
- retained controller must not be described as current APK authority;
- any real local compiler executor requires its own bounded capability/security/build audit.

## Failure signatures

- build.submit starts executing arbitrary local commands without an audited executor;
- docs claim RiftBuild can currently compile/sign APKs;
- Gradle packages riftbuild.js silently;
- native Shell exposes retained `rift build` family;
- app façade reports nativeExecutor=true without a proven executor owner.

## Fix map

Retained controller -> src/riftbuild.js.

Live installed-app build façade -> RiftBrowserAppHost.kt.

Future real local executor -> requires explicit native owner and separate audit.

## Validation

Second audit must prove:
- src/riftbuild.js remains retained;
- no Gradle packaging/native shell activation;
- live build.local methods are fixed and capability-gated;
- build.submit still refuses execution;
- no current native localBuildExecutor implementation is reachable.
