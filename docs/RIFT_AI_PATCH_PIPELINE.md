# AI-Assisted Patch Pipeline

## Status

**RETIRED IMPLEMENTATION — 2026-09-19**

The former Experimental RiftCLI patch lifecycle (`RiftCliPatchLifecycleV1`, its research ledger, documentation-parity planner and verification planner) was intentionally removed when RiftCLI was reset to a native C++ architecture.

This file remains only as an architectural-history marker so older patch notes and discussions do not silently point at a missing system.

The current RiftCLI contract is:

- [Native RiftCLI architecture](systems/riftcli/README.md)

## Preserved engineering principles

The reset does **not** discard the useful engineering rules learned from the retired lifecycle.

Future native RiftCLI gates must preserve these principles:

- understand repository architecture before mutation;
- bind work to actual project/source identity;
- research uncertain external assumptions;
- document intended architecture and rollback;
- patch through narrow existing authorities;
- derive impact from repository truth instead of author claims;
- verify documentation, code, security, dependencies, tests, builds and end-to-end behavior when applicable;
- retain exact evidence/checkpoint identity;
- fail closed on stale or incomplete evidence;
- keep AI reasoning separate from local mutation authority;
- never let a model response silently promote trust or publication state.

## Current behavior

Bootstrap-0 RiftCLI does **not** implement an AI patch lifecycle.

It currently has:

- native C++ core;
- thin Kotlin JNI host;
- help/status/architecture;
- explicit process-local enable/disable;
- zero mutation/tool/network/model authority.

The professional-engineering workflow will be rebuilt natively in gated stages rather than restoring the retired Kotlin implementation.
