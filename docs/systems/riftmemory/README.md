# RiftMemory — Retained Cache/Workset Controller Design

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Classification

RiftMemory is retained/inactive reference source.

Retained implementation:
- src/riftmemory-control.js

Current Android source contains no native RiftMemory owner or command, and Gradle does not package the module.

The future RiftCLI N2 memory architecture is a **separate roadmap** and does not activate this retained cache controller:

- `N2_FEDERATED_MEMORY_ROADMAP.md` — one canonical Rift Memory Kernel, multiple specialist cognitive engines, SQLite-first replaceable storage, RiftStore interface/conformance experimentation, Observer/Validator reconciliation and hard pre-N3 correctness-promotion gates.
- `../../../riftmemory/n2-contract-v1.json` — machine-readable N2.0 terminology, MemoryStore API, correctness corpus, zero-tolerance thresholds, installed baseline and benchmark-deferral contract.
- `../../../riftmemory/n2-phase-authority.json` — machine-readable N2.0-N2.12 lifecycle authority.

N2.0 is promoted on installed source `f6bf12b9fb452cc128e9290fd73599297ba134f2`, Builder run 341, while the N2 runtime remains inactive. N2.1-N2.11 are executed as six macro patches (1+2, 3+4, 5+6, 7+8, 9, 10+11) without merging their individual evidence/promotion gates; N2.12 stays separate. The retained cache controller is still not packaged or authoritative. Comparative/performance benchmarking is deferred until the entire RiftCLI stack is 100% complete and live.

## Source ownership

Retained implementation:
- `src/riftmemory-control.js`

Roadmap-only N2 specification:
- `docs/systems/riftmemory/N2_FEDERATED_MEMORY_ROADMAP.md`

There is no current Kotlin/native RiftMemory owner. The N2 roadmap intentionally has no active runtime owner yet. Any future native Rift Memory Kernel, MemoryStore, specialist engine or RiftStore source requires explicit new ownership entries and a fresh source audit rather than inheriting authority from this retained document.

## Retained design

The JavaScript describes:
- /system/riftmemory/v1;
- warm cache under /system/riftmemory/v1/cache;
- SHA-256-addressed cached objects;
- prefetch;
- pin/unpin;
- status;
- prune;
- flush.

It depends on retained RiftVault for content storage.

Its status explicitly describes the hot tier as Android process memory **not directly managed in this MVP** and nativeAccelerator=false.

The proposed C++/JNI accelerator is not active.

## Activation proof

Verified current state:
- zero Kotlin `RiftMemory` references;
- zero native `rift memory` shell command;
- zero Gradle include for riftmemory-control.js;
- no wildcard src/** packaging;
- only retained JS family consumes globalThis.RiftMemory;
- legacy generic RiftLocalPlatform shell wrapper is retired.

## Virtual-memory wording

RiftMemory is not current Android virtual RAM, swap, paging or memory expansion.

The retained design is a local flash/cache control-plane concept.

Do not describe it as increasing device RAM or providing live remote-backed memory.

## Trust rule

VERIFIED status for this README means the **inactive retained classification** is verified.

It does not certify the retained cache/prune algorithms or any future C++/JNI accelerator.

## Critical invariants

- retained source remains unpackaged;
- no current RAM-expansion claim is made;
- RiftVault dependency remains reference-only;
- no native cache accelerator is implied by the retained design;
- future activation requires explicit ownership, resource bounds and storage/wear analysis;
- the N2 roadmap does not make the retained cache controller live;
- N3 must remain blocked until the roadmap's N2.12 promotion gate is actually satisfied.

## Failure signatures

- docs call RiftMemory current virtual RAM;
- native shell advertises `rift memory`;
- Gradle packages riftmemory-control.js silently;
- nativeAccelerator is claimed active without a native implementation;
- retained cache control is confused with Android memory management;
- roadmap-only N2 capabilities are described as implemented/current;
- N3 begins before N2.12 promotion evidence exists.

## Fix map

Retained design -> src/riftmemory-control.js.

Historical wrapper -> src/riftlocal-platform.js.

Future native cache/data-plane -> requires new owner and audit.

Future RiftCLI N2 cognitive memory -> `N2_FEDERATED_MEMORY_ROADMAP.md`; every implemented source owner must be added here and to `docs/SOURCE_OWNERSHIP.md` when implementation begins.

## Validation

Second audit must prove:
- retained source exists;
- zero Kotlin/native activation;
- zero Gradle packaging;
- no live shell command;
- references remain retained-only;
- public-surface docs classify it as retained.
