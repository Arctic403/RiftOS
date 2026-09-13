# AI Site Adapter Registry

## Purpose

The AI adapter registry isolates DOM selectors and site-specific page semantics from the generic browser MCP compatibility state machine.

## Source ownership

- `android/app/src/main/assets/adapters/ai-adapter-registry.js` — the single active registry, all supported site selector definitions, and `resolve(hostname)` logic.
- `riftbrowser-mcp-app.js` consumes `window.RiftAIAdapters.current()` and selector lists.

The former per-site marker modules were removed because nothing imported or injected them; keeping duplicate definitions beside the live registry made selector ownership ambiguous.

## Why this boundary exists

AI web clients change markup independently. Putting all selectors directly into the tool-loop logic would make one site's redesign risky to every other supported site. The registry lets the transport ask for semantic roles such as assistant turns, user turns, composer, send/stop controls and project/chat links without owning per-site CSS selectors.

## Runtime flow

```text
current location hostname
  -> RiftAIAdapters.resolve(hostname)
  -> site adapter
  -> generic compatibility code queries semantic selector groups
```

## Critical invariants

- Selectors identify visible semantic UI only; they do not scrape private backend APIs.
- Site adapters must not acquire filesystem/native authority.
- New selectors should be as narrow as practical and avoid matching historical/navigation elements as active chat turns.
- The generic adapter must tolerate a missing selector group by degrading functionality instead of granting more authority.

## Failure signatures

- One AI site stops detecting messages while others work -> its adapter selectors.
- Composer found but send/stop state wrong -> composer/control selector group.
- Old messages treated as new turns -> assistant/user turn selectors too broad or baseline logic in generic adapter.
- Site host resolves to wrong adapter -> `resolve(hostname)`.

## Fix map

Patch only the affected site's selector definition when the problem is markup drift. Patch generic `riftbrowser-mcp-app.js` only when the behavior is common across sites.

## Validation

Run `scripts/test-rift-ai-adapters.mjs`. For selector changes, manually verify the target site with empty/new/existing conversations, streaming output and visible project/chat navigation where supported.
