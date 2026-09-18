# Historical RiftBrowser Transport Audit

## Status

**HISTORICAL — NOT CURRENT RUNTIME DOCUMENTATION.**

This file records failures and experiments from the retired shell-WebView/task-orchestration architecture. It must not be used to infer current RiftOS renderer placement, shell ownership, task orchestration or transport lifecycle.

The current source has:
- no trusted shell WebView;
- no Rift AI task/session controller;
- no RiftShell bridge;
- explicit RiftBrowser-owned Chromium surfaces;
- process-owned native MCP/RiftShell authority.

Current browser/MCP behavior must be established from `RiftBrowser*`, `RiftMcp*`, `RiftToolHost.kt`, the browser compatibility asset and their audited subsystem docs.

## Historical value

The old findings remain useful only as regression context: hidden WebViews can be throttled, UI automation needs acknowledgement/correlation, and model-facing transport must remain bounded. Those observations do not mean the former architecture still exists.
