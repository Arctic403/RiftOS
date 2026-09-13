# RiftRT iframe Engine

## Purpose

The `iframe` engine runs compatible Rift app HTML inside a sandboxed frame hosted in a normal RiftDesktop window. It preserves the original `.rift` application model while using the shared RiftRT lifecycle.

## Source ownership

Implementation: `launchIframe`, `iframeHtml`, materialized asset handling and shared host bridge code in `src/riftrt.js`. Package validation/installation remains in `src/riftapps.js`.

## Runtime flow

```text
rift package
  -> RiftRT parseRuntime(engine=iframe)
  -> sandboxed iframe HTML
  -> constrained Rift host bridge
  -> capability/storage/window services
```

## Boundaries

The frame does not receive unrestricted native Android authority. Filesystem/clipboard/etc access remains brokered. Desktop behavior remains owned by RiftDesktop.

## Failure signatures

- Frame is blank -> generated iframe HTML/assets/CSP/entry content.
- Frame loads but host calls fail -> token/message correlation or shared capability broker.
- Package works in legacy manager but not RiftRT -> runtime spec/entry mapping.

## Fix map

HTML materialization/frame launch -> `launchIframe`/`iframeHtml`. Package content -> app system. Host capability failure across multiple engines -> shared RiftRT broker rather than iframe code.

## Validation

Launch a packaged HTML app, resolve packaged assets, exercise allowed/denied host calls, resize/minimize/restore, close/relaunch, and verify one bad frame cannot break the shell.