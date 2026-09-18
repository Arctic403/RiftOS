# RiftMemory — Retained Cache/Workset Controller Design

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-17.**

## Classification

RiftMemory is retained/inactive reference source.

Retained implementation:
- src/riftmemory-control.js

Current Android source contains no native RiftMemory owner or command, and Gradle does not package the module.

## Source ownership

Retained implementation:
- `src/riftmemory-control.js`

There is no current Kotlin/native RiftMemory owner. Any future native accelerator requires a fresh ownership audit rather than inheriting this retained document's authority.

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
- future activation requires explicit ownership, resource bounds and storage/wear analysis.

## Failure signatures

- docs call RiftMemory current virtual RAM;
- native shell advertises `rift memory`;
- Gradle packages riftmemory-control.js silently;
- nativeAccelerator is claimed active without a native implementation;
- retained cache control is confused with Android memory management.

## Fix map

Retained design -> src/riftmemory-control.js.

Historical wrapper -> src/riftlocal-platform.js.

Future native cache/data-plane -> requires new owner and audit.

## Validation

Second audit must prove:
- retained source exists;
- zero Kotlin/native activation;
- zero Gradle packaging;
- no live shell command;
- references remain retained-only;
- public-surface docs classify it as retained.
