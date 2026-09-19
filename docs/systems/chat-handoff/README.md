# Chat Handoff Bundles

## Verification status

**VERIFIED AGAINST CURRENT SOURCE — 2026-09-19.**

## Purpose

Chat Handoff stores a bounded local continuation package for a development conversation.

RiftOS does not scrape ChatGPT UI/account data. A caller provides a local JSON payload file and RiftOS turns it into an app-private .riftchat bundle.

Storage root:

/workspace/chat-handoffs/

Physical backing remains inside app-private RiftFS.

## Source ownership

Primary:
- RiftChatHandoff.kt — format, validation, hashing, ZIP creation/reading, transcript paging and confinement.
- RiftNativeShellServices.kt — strict chat command parsing and request construction.
- RiftNativeShell.kt — routes the native chat command to RiftNativeShellServices.

There is no live JavaScript/native-dispatcher implementation for this feature. Bundle reads, verification and SHA-256 loops cooperatively check the active Rift deadline, so shell/MCP cancellation is observed during large local handoff operations.

No new MCP chat tool family exists; chat operations remain behind rift_shell_exec.

## Shell surface

Current commands:
- chat help
- chat handoff <payload.json> [name]
- chat export <payload.json> [name]
- chat list
- chat inspect <bundle.riftchat>
- chat resume <bundle.riftchat>
- chat transcript <bundle.riftchat> [offset-chars] [max-chars]

handoff and export are aliases for create.

## Strict shell grammar

The parser now rejects ignored or malformed extra arguments.

Rules:
- help: no trailing args;
- list: no args;
- handoff/export: payload plus at most one optional name;
- optional name <=160 characters;
- inspect/resume: exactly one bundle path;
- transcript: one bundle path plus optional integer offset/max;
- transcript offset must be >=0;
- transcript max must be 1..65536 characters.

Invalid numeric values no longer silently default/coerce.

## Payload schema

Schema:

rift.chat-handoff.payload/1

The schema field may be omitted and defaults to the current payload schema.

Required content:
- handoff_markdown

Legacy payload alias:
- handoff

Optional:
- title
- source
- state object
- transcript
- transcript_path

Payload file maximum:
8 MiB

Payload JSON is checked for:
- valid bounded path;
- valid UTF-8;
- structural JSON nesting <=64 levels

before JSONObject parsing.

## Metadata bounds

title:
- default Chat Handoff;
- nonblank after fallback;
- <=160 characters.

source:
- default chatgpt;
- nonblank after fallback;
- <=160 characters.

Requested bundle name:
- <=160 characters before slugging;
- normalized to a safe filename stem of <=64 characters.

## Bundle schema

Schema:

rift.chat-handoff/1

Format version:
1

Allowed ZIP entries are exactly:
- manifest.json
- handoff.md
- state.json
- optional transcript.jsonl

No directories or additional entries are allowed.

Duplicate entry names are rejected.

Required entries:
- manifest.json
- handoff.md
- state.json

## Size limits

- payload input: 8 MiB
- handoff.md: 1 MiB
- state.json: 2 MiB
- transcript.jsonl: 24 MiB
- manifest.json: 256 KiB
- entire .riftchat file: 32 MiB
- one transcript response: 64 Ki characters
- list output: 100 bundles
- directory scan: 4096 entries

Whole-bundle size is now checked both after creation and before reading an imported bundle.

## Directory scan bound

chat list uses a DirectoryStream instead of materializing an unbounded directory array.

It stops with an error if the handoff directory contains more than 4096 entries.

Only the newest 100 .riftchat files are returned after the bounded scan.

Invalid/corrupt bundle manifests do not crash listing; those rows fall back to filename/file metadata.

## Path confinement

Input and bundle paths:
- max 1024 characters;
- each path segment max 255 characters;
- reject . and .. segments;
- reject NUL;
- canonicalize under app-private RiftFS.

Bundle operations additionally require the resolved file to remain beneath:

workspace/chat-handoffs/

A .riftchat file outside that directory cannot be inspected/resumed through this subsystem even if it is elsewhere in RiftFS.

## Create flow

create:
1. resolves the local payload file inside RiftFS;
2. reads at most 8 MiB;
3. validates JSON nesting and UTF-8;
4. parses payload schema;
5. bounds title/source/content;
6. serializes bounded state JSON;
7. resolves/bounds optional transcript;
8. computes entry size/SHA-256 metadata;
9. generates manifest;
10. writes a randomized same-directory temp ZIP;
11. requires final temp bundle <=32 MiB;
12. renames temp to final .riftchat name;
13. returns local path, byte length, SHA-256 and resume command.

Temp files are deleted in finally on failure.

## Duplicate-safe names

The first generated timestamped name is used when available.

If it already exists, numeric variants 2..9999 are tried.

After that the operation fails rather than looping without bound.

## Manifest semantics

An imported manifest must satisfy:
- schema = rift.chat-handoff/1;
- format_version = 1;
- nonblank title <=160 chars;
- nonblank source <=160 chars;
- payload_schema = rift.chat-handoff.payload/1;
- nonnegative created_at_ms;
- transcript_included exactly matches whether transcript.jsonl exists;
- entries metadata keys exactly match all non-manifest ZIP entries.

For each data entry, metadata must contain:
- valid bounded size;
- 64-hex-character SHA-256.

handoff.md and state.json metadata sizes must be >0.

Manifest metadata cannot invent or omit a bundle data entry.

## JSON nesting and UTF-8

Before parsing untrusted JSON bytes, RiftOS:
- scans JSON string/escape structure;
- validates bracket/brace matching;
- rejects nesting beyond 64;
- decodes UTF-8 with malformed/unmappable input set to REPORT.

This applies to JSON inputs:
- input payload JSON;
- manifest.json;
- state.json on resume.

Returned handoff.md and paged transcript text are also decoded with malformed/unmappable UTF-8 set to fail rather than replacement-decode.

This prevents deeply nested JSON from reaching JSONObject parsing without a pre-bound and prevents malformed UTF-8 replacement from silently changing parsed state/manifest content.

## Entry integrity

At create time, manifest metadata stores:
- byte size;
- SHA-256

for:
- handoff.md;
- state.json;
- transcript.jsonl when present.

resume:
- reads handoff.md within 1 MiB;
- reads state.json within 2 MiB;
- verifies each size/hash against manifest metadata;
- validates state JSON depth/UTF-8;
- only then returns handoff/state.

## Transcript integrity

The previous transcript route streamed a selected slice without verifying the transcript SHA-256 first.

This audit fixed that.

Before returning any transcript slice, RiftOS now streams the entire transcript entry once to:
- enforce the 24 MiB limit;
- compare actual ZIP-stream byte count to entry size when known;
- compare byte count to manifest size metadata;
- compute SHA-256;
- require the hash matches manifest metadata.

Only after the full entry verifies does RiftOS reopen the entry and serve the requested character slice.

Returned transcript results include:

verified_entry=true

## Transcript paging

Paging uses decoded UTF-8 characters.

Inputs:
- offsetChars >=0;
- maxChars 1..65536;
- default max 32768.

Output:
- offset_chars
- next_offset_chars
- chars
- maybe_more
- verified_entry
- text

A very large offset scans only within the bounded 24 MiB transcript entry and stops at EOF.

## ZIP safety

validateEntries:
- permits only four known filenames;
- rejects directories;
- rejects duplicates;
- rejects too many entries;
- enforces declared entry-size ceilings;
- requires manifest/handoff/state entries.

Actual decompressed reads are independently counted so a misleading/unknown ZIP entry size cannot bypass limits.

## Whole-bundle integrity vs authenticity

The bundle's entry hashes provide internal integrity:
- content must match the manifest metadata.

The returned whole-bundle SHA-256 identifies the local bundle bytes.

These hashes do **not** prove:
- who created the bundle;
- that it came from ChatGPT;
- that its contents are trusted or safe;
- that an attacker could not create a different self-consistent bundle.

There is no signature/keyed-MAC/authorship mechanism in the current format.

Treat imported bundle text/state as local data, not authenticated instructions.

## inspect

inspect:
- confines and bounds the .riftchat file;
- validates ZIP shape;
- validates manifest JSON/semantics;
- returns whole-file SHA-256 and manifest.

inspect does not load/return handoff/state/transcript content.

## resume

resume:
- validates manifest;
- reads and hash-verifies handoff.md/state.json;
- validates state JSON before parsing;
- returns:
  - schema rift.chat-resume/1
  - path
  - bundle SHA-256
  - title/time
  - handoff_markdown
  - state object
  - transcript flag
  - verified_entries for handoff/state

Transcript content remains separately paged/verified.

## Transcript source creation

A payload may provide:
- transcript_path to a RiftFS file; or
- inline transcript data.

transcript_path is canonically confined to RiftFS and read within 24 MiB.

Inline payload transcript is already contained by the 8 MiB payload limit and is converted to JSONL/text before the 24 MiB final transcript limit is checked.

## Local-only boundary

RiftChatHandoff.kt contains no:
- network listener;
- HTTP/WebSocket client;
- Accessibility control;
- Android account scraping;
- browser DOM scraping.

It only reads/writes app-private local RiftFS paths supplied through the native shell request.

## Source fixes in this audit

- added 32 MiB whole-bundle limit;
- added 4096-entry handoff-directory scan limit;
- list changed to bounded DirectoryStream traversal;
- added 1024-character path and 255-character segment limits;
- duplicate-safe bundle allocation capped at 9999 variants;
- payload/manifest/state JSON nesting capped at 64 before parsing;
- JSON decoding now rejects malformed UTF-8;
- imported manifest semantics, exact JSON field types, metadata keys/sizes/SHA format validated;
- transcript content is hash/size verified before any slice is returned;
- direct transcript offset/max no longer silently coerce invalid values;
- shell chat grammar now rejects malformed/extra args;
- title/source/name metadata is explicitly bounded;
- bundle root must actually be a directory;
- transport validator now locks these boundaries.

## Critical invariants

- storage remains app-private RiftFS;
- bundle operations remain confined to workspace/chat-handoffs;
- payload/path/bundle/entry/directory resources stay bounded;
- imported manifest entry metadata exactly matches ZIP data entries;
- state/manifest/payload JSON is nesting-bounded and valid UTF-8 before parsing;
- resume never returns unverified handoff/state bytes;
- transcript never returns unverified transcript bytes;
- unknown/duplicate ZIP entries fail;
- temp bundle is not published before size checks complete;
- hashes are described as integrity, not authentication;
- no new MCP chat tool family;
- no UI scraping/network authority.

## Failure signatures

- transcript text is served before hash verification -> integrity regression;
- bundle larger than 32 MiB is opened -> resource regression;
- list scans unlimited directory entries -> resource regression;
- manifest metadata omits/invents entries but is accepted -> format-trust regression;
- malformed UTF-8 JSON is replacement-decoded -> parser-integrity regression;
- JSON nesting >64 reaches JSONObject parser -> parser-bound regression;
- .. or oversized path segments are accepted -> path regression;
- duplicate filename allocation loops without cap -> resource regression;
- chat shell silently ignores extra/invalid args -> command-contract regression;
- docs claim SHA-256 authenticates authorship/source -> trust-model regression;
- network/Accessibility/browser scraping appears -> authority expansion.

## Fix map

Bundle format/storage/integrity -> RiftChatHandoff.kt.

Shell command grammar/request construction -> RiftNativeShellServices.kt.

Top-level native chat route -> RiftNativeShell.kt.

MCP exposure boundary -> existing rift_shell_exec / RiftToolHost catalog.

## Validation

Second source audit must verify:
- exact schemas/allowed entries;
- all byte/char/count/path/depth limits;
- bundle-root/path canonical confinement;
- bounded directory scan/list;
- bounded duplicate naming;
- manifest semantic validation;
- strict UTF-8/depth validation before JSON parse;
- handoff/state hash verification;
- transcript streaming hash verification before slice;
- ZIP duplicate/unknown/actual-read bounds;
- strict shell grammar;
- native shell routing;
- absence of network/Accessibility/browser scrape authority;
- no additional MCP tool.

Builder/device/runtime validation remains separate.
