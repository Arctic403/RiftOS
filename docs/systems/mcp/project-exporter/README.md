# Rift Project Exporter

## Purpose

`RiftProjectExporter` streams a deterministic, bounded full-source snapshot for offline/project-wide audits without forcing a single oversized MCP response.

## Source ownership

`android/app/src/main/java/com/riftos/app/RiftProjectExporter.kt`.

Entry points: `export(...)` and `snapshotId(root)`.

## Export model

The exporter scans eligible source files under a chosen workspace root, computes path/size/SHA-256 metadata and derives a deterministic snapshot ID. Pages are requested with an opaque cursor containing file index/byte offset. Large UTF-8 files are split only at safe UTF-8 boundaries. The client continues using `nextCursor` and the original expected snapshot until `done=true`.

## Exclusions

Build outputs, ignored directories, binary files, known secret/credential extensions and other non-source material are skipped. A bounded sample of skip reasons may be reported. The goal is source auditability, not arbitrary binary exfiltration.

## Why this boundary exists

Code Mode search/ranged reads are ideal for surgical work; a full audit sometimes needs every eligible source byte. Paging keeps relay/tool envelopes below their size limits and makes workspace-change detection explicit.

## Critical invariants

- Continuation must reject if the source snapshot changed.
- Cursor parsing must reject malformed/out-of-range values.
- UTF-8 chunks must not split inside a multibyte character.
- Secret/binary/build exclusions are fail-safe.
- Per-page and total metadata stay deterministic for the same tree.

## Failure signatures

- Continuation says snapshot changed -> source really changed or snapshot scan is nondeterministic.
- File content corrupt at page boundary -> UTF-8 boundary logic.
- Sensitive/non-source file appears -> `skipReason`/binary detection regression.
- Huge response -> max-byte clamp/page assembly.

## Fix map

Full-project export filtering/paging/snapshot semantics belong here. Ordinary targeted reads/search belong in `RiftToolSandbox`. Server-side duplicate structured summary behavior belongs in `RiftMcpServer`.

## Validation

Test empty projects, many files, one large UTF-8 file with multibyte characters, binary/secret/build exclusions, cursor continuation, modified source between pages and deterministic snapshot IDs across repeated unchanged exports.
