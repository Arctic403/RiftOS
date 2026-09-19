# Rift Project Exporter

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

`RiftProjectExporter` produces deterministic paged UTF-8 source snapshots for project-wide audits without one unbounded MCP payload.

## Source ownership

Primary:
- `RiftProjectExporter.kt`

Callers:
- `RiftToolSandbox` workspace export and expected-export-snapshot guard.
- `RiftMcpServer` summarizes export structured content to avoid duplicating raw page source.

## Deterministic scan

Eligible files are walked recursively and sorted by project-relative path.

Each source row records:
- path;
- byte size;
- SHA-256.

Snapshot id is SHA-256 over the ordered sequence of path + file SHA pairs.

Unchanged eligible source therefore produces the same snapshot id regardless of paging.

## Exclusions

Ignored directories include common VCS/build/cache/vendor/virtual-env outputs.

Files are skipped when:
- known secret/config name;
- name begins `.env`;
- secret extension such as keystore/jks/p12/pfx/pem/key;
- known binary extension;
- text file exceeds 16 MiB;
- first 8192 bytes contain NUL.

Skip counts are reported on the first page, with at most 120 sampled skipped entries.

This exporter is for source audit, not arbitrary binary/credential exfiltration.

## Page bounds

Requested page size is clamped:
- minimum 64 KiB;
- default 320 KiB;
- maximum 400 KiB.

One source chunk is at most 96 KiB.

File discovery, page assembly and snapshot hashing cooperatively check the active `RiftDeadline`; when called through the MCP sandbox they therefore terminate within the sandbox request lifecycle instead of continuing after the caller has timed out.

Rows are reduced as needed to fit the page budget. A tiny first row may still be emitted to make progress.

`responseBytes` reports the final encoded response size.

## Cursor

Cursor format:
`<fileIndex>:<byteOffset>`

Blank or `0` means `0:0`.

Validation:
- two numeric components;
- file index 0..fileCount;
- nonnegative offset;
- end-of-project cursor requires offset 0;
- per-file offset must not exceed file bytes;
- a nonterminal offset must land on a UTF-8 code-point boundary.

Chunk end is also backed up to a valid UTF-8 boundary.

The UTF-8 start-boundary and terminal-cursor checks were added during this audit to reject forged/misaligned continuation cursors.

## Snapshot continuation

Every page computes the current eligible-source snapshot.

When `expectedSnapshot` is supplied and differs, export aborts and tells the caller to restart from cursor 0.

A correct paged client carries the initial snapshot id into every continuation request.

## Entry format

Each returned chunk includes:
- path;
- whole-file SHA-256;
- whole-file size;
- byteStart;
- byteEnd;
- complete;
- UTF-8 content.

`nextCursor` is null only when the entire eligible source set is complete.

## Non-ownership boundaries

Exporter does not own:
- workspace containment -> Sandbox;
- targeted reads/search -> Sandbox;
- MCP response framing -> Server;
- mutation or patching.

## Critical invariants

- deterministic sorted scan;
- snapshot covers every eligible source file hash;
- secrets/binaries/build outputs excluded;
- max source file 16 MiB;
- page 64..400 KiB;
- chunk <=96 KiB;
- cursor cannot split UTF-8;
- changed source invalidates continuation;
- exporter never mutates workspace;
- scan/page/hash loops honor the active cooperative deadline.

## Failure signatures

- unchanged tree produces different snapshot -> ordering/hash regression;
- changed source continues under old snapshot -> stale-audit regression;
- secret/binary appears -> filtering regression;
- UTF-8 corruption at continuation -> cursor/boundary regression;
- forged end cursor with nonzero offset accepted -> cursor regression;
- response grows far beyond configured page budget -> paging regression.

## Fix map

Scanning/filtering/snapshot/cursor/chunking -> `RiftProjectExporter.kt`.

Workspace scope -> Sandbox.

MCP structured-content compaction -> Server.

## Validation

Second audit must recheck ignored/secret/binary rules, deterministic ordering, snapshot formula, 64/320/400 KiB page bounds, 96 KiB chunks, 16 MiB source cap, skip-sample 120, cursor/end/UTF-8 validation and expected-snapshot rejection.
