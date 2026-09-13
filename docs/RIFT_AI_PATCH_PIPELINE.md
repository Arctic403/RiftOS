# AI-Assisted Patch Workflow

The filename is historical. This document describes a development workflow over the active Rift MCP/workspace tools; it is **not** a Rift AI runtime subsystem, task controller or persistent journal.

## Goal
A safe project modification flow for RiftOS AI-assisted development.

## Pipeline

1. Snapshot
- Create a scoped workspace snapshot before mutation.
- Store the snapshot id with the patch session.

2. Audit
- Run project health audit.
- Run focused scans for security, runtime, and architecture.
- Inspect references before editing.

3. Inspect
- Pull exact symbols/ranges required for the change.
- Avoid replacing whole files unless required.

4. Patch
- Apply surgical hunks or transactional multi-file edits.
- Require expected snapshot/hash guards for risky changes.

5. Validate
- Run dry-run validation before commit.
- Confirm affected files and change counts.

6. Build/Test
- Run the relevant Android/web build checks.
- Capture failures as the next patch input.

7. Commit
- Return a final change report:
  - files changed
  - added/removed lines
  - validation result
  - build result
  - rollback snapshot id

## Rules

- Prefer workspace patches over archive extraction.
- Never blindly replace generated or unrelated files.
- Keep subsystem changes isolated.
- Roll back when validation fails.
